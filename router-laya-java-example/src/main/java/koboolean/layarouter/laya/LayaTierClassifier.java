package koboolean.layarouter.laya;

import com.convaiinnovations.laya.Answer;
import com.convaiinnovations.laya.Prediction;
import com.convaiinnovations.laya.Predictor;
import com.convaiinnovations.laya.Question;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Laya adapter: owns the single Laya {@link Predictor} (in production a {@code Router} over the
 * english and multilingual checkpoints) and turns its answer into a {@link WorkerTier}. Laya
 * decides what the request <em>means</em> - its complexity tier - and nothing else.
 * <p>
 * Create it once at startup - never per request. laya-java documents that one ONNX session is
 * not safe for concurrent {@code predict} calls, so calls are serialized with a lock rather than
 * allowed to run concurrently on the one model instance.
 */
public class LayaTierClassifier implements AutoCloseable {

    public static final String WARM_UP_PROMPT = "Hello";
    public static final String QUESTION_KEY = "worker";

    public static final Map<String, Question> QUESTIONS = questions();

    private static final HttpStatus BAD_GATEWAY = HttpStatus.BAD_GATEWAY;

    private final Supplier<? extends Predictor> engineFactory;
    private final ReentrantLock predictLock = new ReentrantLock();

    private volatile Predictor engine;
    private volatile boolean ready;

    public LayaTierClassifier(Supplier<? extends Predictor> engineFactory) {
        this.engineFactory = engineFactory;
    }

    // Options are positional in Laya, so the criteria must keep their order: a LinkedHashMap,
    // never Map.of (which laya-java refuses rather than silently reorders).
    private static Map<String, Question> questions() {
        Map<String, String> criteria = new LinkedHashMap<>();
        criteria.put(WorkerTier.SMALL.label(), "Simple questions, short conversations, basic tasks");
        criteria.put(WorkerTier.MEDIUM.label(), "Moderate reasoning, coding, or analysis");
        criteria.put(WorkerTier.POWERFUL.label(), "Complex reasoning, architecture, difficult coding tasks");

        Map<String, Question> questions = new LinkedHashMap<>();
        questions.put(QUESTION_KEY, Question.choice("Which worker class is most appropriate for this request?", criteria));
        return Collections.unmodifiableMap(questions);
    }

    public boolean isReady() {
        return ready;
    }

    /**
     * Creates the Laya engine and runs one short predict, so the checkpoint is loaded before any
     * real routing request arrives. A failure here propagates and fails application startup.
     */
    public void warmUp() {
        if (engine == null) {
            engine = engineFactory.get();
        }
        predict(WARM_UP_PROMPT);
        ready = true;
    }

    public TierDecision classify(String prompt) {
        if (!ready) {
            throw new ResponseStatusException(BAD_GATEWAY, "The Laya model is not loaded yet.");
        }

        Prediction prediction;
        try {
            prediction = predict(prompt);
        } catch (RuntimeException e) {
            throw new ResponseStatusException(BAD_GATEWAY, "The Laya predict call failed: " + e.getMessage(), e);
        }

        Answer answer = prediction == null ? null : prediction.answer(QUESTION_KEY);
        if (!(answer instanceof Answer.Choice choice)) {
            throw new ResponseStatusException(BAD_GATEWAY, "Laya returned no choice answer for '" + QUESTION_KEY + "'.");
        }

        WorkerTier tier = WorkerTier.fromLabel(choice.choice())
                .orElseThrow(() -> new ResponseStatusException(BAD_GATEWAY,
                        "Laya returned a choice that was not offered: '" + choice.choice() + "'"));
        return new TierDecision(tier, choice.probabilities());
    }

    private Prediction predict(String prompt) {
        predictLock.lock();
        try {
            return engine.predict(prompt, QUESTIONS);
        } finally {
            predictLock.unlock();
        }
    }

    @Override
    public void close() throws Exception {
        ready = false;
        if (engine instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
