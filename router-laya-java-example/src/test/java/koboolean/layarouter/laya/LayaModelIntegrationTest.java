package koboolean.layarouter.laya;

import com.convaiinnovations.laya.Router;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real laya-java Router over the prepared english + multilingual checkpoints. Skipped
 * unless LAYA_CHECKPOINTS and LAYA_ONNX_ROOT are set - see the module README.
 * <p>
 * What the model answers for a given prompt is a judgement, not a contract, so only the shape is
 * asserted: a tier that was offered and a probability distribution over exactly the three options.
 */
class LayaModelIntegrationTest {

    private static LayaTierClassifier classifier;

    @BeforeAll
    static void loadModel() {
        String checkpoints = System.getenv("LAYA_CHECKPOINTS");
        String graphs = System.getenv("LAYA_ONNX_ROOT");
        assumeTrue(checkpoints != null && !checkpoints.isBlank() && graphs != null && !graphs.isBlank(),
                "LAYA_CHECKPOINTS / LAYA_ONNX_ROOT are not set");

        classifier = new LayaTierClassifier(() -> Router.builder()
                .checkpointsRoot(Path.of(checkpoints), Path.of(graphs))
                .build()
                .preload(List.of("english", "multilingual")));
        classifier.warmUp();
    }

    @AfterAll
    static void close() throws Exception {
        if (classifier != null) {
            classifier.close();
        }
    }

    @Test
    void classifiesEnglishAndNonEnglishPromptsIntoAnOfferedTier() {
        for (String prompt : List.of(
                "Hi, what's 2+2?",
                "Write a Python function that parses CSV with quoted fields.",
                "Design a distributed consensus protocol tolerant to Byzantine faults and prove its safety.",
                "안녕하세요, 오늘 날씨 어때요?")) {
            TierDecision decision = classifier.classify(prompt);

            System.out.printf("%-8s %s | %s%n", decision.tier().label(), decision.probabilities(), prompt);
            assertThat(decision.tier()).isNotNull();
            assertThat(decision.probabilities()).containsOnlyKeys("small", "medium", "powerful");
            assertThat(decision.probabilities().values().stream().mapToDouble(Double::doubleValue).sum())
                    .isCloseTo(1.0, offset(1e-3));
        }
    }
}
