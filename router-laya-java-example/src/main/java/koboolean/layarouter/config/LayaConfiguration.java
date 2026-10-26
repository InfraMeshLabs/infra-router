package koboolean.layarouter.config;

import com.convaiinnovations.laya.Router;
import koboolean.layarouter.laya.LayaTierClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates the one {@link LayaTierClassifier} for the lifetime of the process and loads the model
 * before the Router serves anything: bean creation blocks startup, and a missing checkpoint or
 * graph fails startup instead of failing the first routing request.
 */
@Configuration(proxyBeanMethods = false)
public class LayaConfiguration {

    private static final Logger log = LoggerFactory.getLogger(LayaConfiguration.class);

    @Bean(destroyMethod = "close")
    public LayaTierClassifier layaTierClassifier(
            LayaRouterProperties properties,
            @Value("classpath:prompts/routing-system-prompt.st") Resource systemPromptResource
    ) {
        Path checkpoints = requireDirectory(properties.getCheckpointsRoot(), "router.laya.checkpoints-root (LAYA_CHECKPOINTS)");
        Path graphs = requireDirectory(properties.getOnnxRoot(), "router.laya.onnx-root (LAYA_ONNX_ROOT)");

        LayaTierClassifier classifier = new LayaTierClassifier(() -> {
            Router router = Router.builder()
                    .checkpointsRoot(checkpoints, graphs)
                    .maxLoaded(properties.getMaxLoaded())
                    .build();
            if (!properties.getPreload().isEmpty()) {
                router.preload(properties.getPreload());
            }
            return router;
        }, readResource(systemPromptResource));

        long started = System.nanoTime();
        classifier.warmUp();
        log.info("Laya warm-up completed in {} ms; Router is READY.", (System.nanoTime() - started) / 1_000_000);
        return classifier;
    }

    private static String readResource(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load Laya prompt: " + resource, e);
        }
    }

    private static Path requireDirectory(Path path, String name) {
        if (path == null || path.toString().isBlank()) {
            throw new IllegalStateException(name + " is not set. Prepare the checkpoints with laya-java's "
                    + "scripts/prepare_checkpoint.py and point this at the result - see the module README.");
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException(name + " is not a directory: " + path.toAbsolutePath());
        }
        return path;
    }
}
