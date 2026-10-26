package koboolean.layarouter.laya;

import com.convaiinnovations.laya.Answer;
import com.convaiinnovations.laya.Prediction;
import com.convaiinnovations.laya.Predictor;
import com.convaiinnovations.laya.Question;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Laya itself is always replaced by a fake engine: what the model judges for a given prompt is
 * not deterministic and is deliberately not asserted here (see LayaModelIntegrationTest).
 */
class LayaTierClassifierTest {

    private static final Map<String, Double> PROBABILITIES = Map.of("small", 0.1, "medium", 0.6, "powerful", 0.3);

    static class FakeEngine implements Predictor {

        final List<String> prompts = new ArrayList<>();
        Answer answer;
        RuntimeException error;

        FakeEngine(String choice) {
            this.answer = new Answer.Choice(choice, PROBABILITIES, 0.5, 0.6, 1.0);
        }

        @Override
        public Prediction predict(Object state, Map<String, Question> questions) {
            assertThat(questions).isSameAs(LayaTierClassifier.QUESTIONS);
            prompts.add((String) state);
            if (error != null && !LayaTierClassifier.WARM_UP_PROMPT.equals(state)) {
                throw error;
            }
            return new Prediction(Prediction.MODEL, answer == null ? Map.of() : Map.of("worker", answer), null);
        }
    }

    private static LayaTierClassifier warmedUp(FakeEngine engine) {
        LayaTierClassifier classifier = new LayaTierClassifier(() -> engine);
        classifier.warmUp();
        return classifier;
    }

    @Test
    void createsTheEngineOnceAndBecomesReadyOnlyAfterWarmUp() {
        AtomicInteger created = new AtomicInteger();
        FakeEngine engine = new FakeEngine("medium");
        LayaTierClassifier classifier = new LayaTierClassifier(() -> {
            created.incrementAndGet();
            return engine;
        });

        assertThat(classifier.isReady()).isFalse();

        classifier.warmUp();
        classifier.classify("first");
        classifier.classify("second");

        assertThat(classifier.isReady()).isTrue();
        assertThat(created).hasValue(1);
        assertThat(engine.prompts).containsExactly(LayaTierClassifier.WARM_UP_PROMPT, "first", "second");
    }

    @Test
    void isNotReadyWhenWarmUpFails() {
        LayaTierClassifier classifier = new LayaTierClassifier(() -> (state, questions) -> {
            throw new IllegalStateException("graph not found");
        });

        assertThatThrownBy(classifier::warmUp).isInstanceOf(IllegalStateException.class);
        assertThat(classifier.isReady()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"small,SMALL", "medium,MEDIUM", "POWERFUL,POWERFUL"})
    void convertsTheLayaChoiceToAWorkerTier(String choice, WorkerTier tier) {
        TierDecision decision = warmedUp(new FakeEngine(choice)).classify("anything");

        assertThat(decision.tier()).isEqualTo(tier);
        assertThat(decision.probabilities()).isEqualTo(PROBABILITIES);
    }

    @Test
    void offersTheThreeTiersInCapabilityOrder() {
        Question question = LayaTierClassifier.QUESTIONS.get(LayaTierClassifier.QUESTION_KEY);

        assertThat(question.labels()).containsExactly("small", "medium", "powerful");
    }

    @Test
    void reportsAChoiceThatWasNotOfferedAsBadGateway() {
        assertBadGateway(warmedUp(new FakeEngine("gigantic")));
    }

    @Test
    void reportsAMissingAnswerAsBadGateway() {
        FakeEngine engine = new FakeEngine("small");
        LayaTierClassifier classifier = warmedUp(engine);
        engine.answer = null;

        assertBadGateway(classifier);
    }

    @Test
    void reportsAnAnswerOfTheWrongTypeAsBadGateway() {
        FakeEngine engine = new FakeEngine("small");
        LayaTierClassifier classifier = warmedUp(engine);
        engine.answer = new Answer.Noul(0.9, 0.9, 0.9, 1.0);

        assertBadGateway(classifier);
    }

    @Test
    void reportsAPredictFailureAsBadGateway() {
        FakeEngine engine = new FakeEngine("small");
        engine.error = new IllegalStateException("boom");

        assertBadGateway(warmedUp(engine));
    }

    @Test
    void refusesToClassifyBeforeWarmUp() {
        assertBadGateway(new LayaTierClassifier(() -> new FakeEngine("small")));
    }

    @Test
    void closesAnAutoCloseableEngine() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        class CloseableEngine extends FakeEngine implements AutoCloseable {
            CloseableEngine() {
                super("small");
            }

            @Override
            public void close() {
                closed.incrementAndGet();
            }
        }
        LayaTierClassifier classifier = warmedUp(new CloseableEngine());

        classifier.close();

        assertThat(closed).hasValue(1);
        assertThat(classifier.isReady()).isFalse();
    }

    private static void assertBadGateway(LayaTierClassifier classifier) {
        assertThatThrownBy(() -> classifier.classify("anything"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(502));
    }
}
