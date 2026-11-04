package koboolean.layarouter.service.impl;

import com.inframesh.node.dto.router.RoutingRequest;
import com.inframesh.node.dto.router.RoutingResponse;
import com.inframesh.node.dto.router.WorkerCandidate;
import koboolean.layarouter.config.LayaRouterProperties;
import koboolean.layarouter.laya.LayaTierClassifier;
import koboolean.layarouter.laya.TierDecision;
import koboolean.layarouter.laya.WorkerTier;
import koboolean.layarouter.policy.DataRoutingGuard;
import koboolean.layarouter.policy.LocalOnlyRequirement;
import koboolean.layarouter.service.PromptBuilder;
import koboolean.layarouter.service.RouterService;
import koboolean.layarouter.service.WorkerSelector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/**
 * Worker selection with Laya judging request complexity - the JVM counterpart of the Python
 * {@code router-laya-example}, with the same policy:
 * <ol>
 *     <li>whether the request must stay on LOCAL-only Workers - privateData, then the Team's
 *     DataRoutingPolicy ({@link DataRoutingGuard});</li>
 *     <li>RoutingHints hard filters / soft preference ({@link WorkerSelector}) - before Laya is
 *     called, so a request no Worker may serve never costs a forward pass;</li>
 *     <li>the user prompt ({@link PromptBuilder}) is classified by Laya into a
 *     {@code WorkerTier} ({@link LayaTierClassifier}), escalated when the answer is ambiguous
 *     ({@link TierDecision#requiredTier(Double)});</li>
 *     <li>capability tier from configuration, then the lowest Load Score
 *     ({@link WorkerSelector}), re-checked against the LOCAL-only requirement.</li>
 * </ol>
 * Unlike {@code router-jev-example}, a Laya failure is not papered over with a fallback: Laya is
 * local, so a failure means the model itself is broken, and it is reported as 502.
 */
@Service
public class LayaRouterServiceImpl implements RouterService {

    private static final Logger log = LoggerFactory.getLogger(LayaRouterServiceImpl.class);

    private static final HttpStatus UNPROCESSABLE_ENTITY = HttpStatus.valueOf(422);

    private final LayaTierClassifier classifier;
    private final PromptBuilder promptBuilder;
    private final WorkerSelector workerSelector;
    private final DataRoutingGuard dataRoutingGuard;
    private final Double confidenceThreshold;

    public LayaRouterServiceImpl(LayaTierClassifier classifier, PromptBuilder promptBuilder,
                                 WorkerSelector workerSelector, DataRoutingGuard dataRoutingGuard,
                                 LayaRouterProperties properties) {
        this.classifier = classifier;
        this.promptBuilder = promptBuilder;
        this.workerSelector = workerSelector;
        this.dataRoutingGuard = dataRoutingGuard;
        this.confidenceThreshold = properties.getConfidenceThreshold();
    }

    @Override
    public RoutingResponse route(RoutingRequest request) {
        long totalStarted = System.nanoTime();

        long selectionStarted = System.nanoTime();
        Optional<LocalOnlyRequirement> localOnly = dataRoutingGuard.localOnlyRequirement(request);
        List<WorkerCandidate> eligible = workerSelector.filterByHints(request.workers(), request.routing(), localOnly);
        long selectionNanos = System.nanoTime() - selectionStarted;

        String prompt = promptBuilder.build(request, eligible)
                .orElseThrow(() -> new ResponseStatusException(UNPROCESSABLE_ENTITY,
                        "The routing request contains no message content to route on."));

        long layaStarted = System.nanoTime();
        TierDecision decision = classifier.classify(prompt);
        long layaNanos = System.nanoTime() - layaStarted;

        WorkerTier requiredTier = decision.requiredTier(confidenceThreshold);

        selectionStarted = System.nanoTime();
        WorkerCandidate selected = workerSelector.select(requiredTier, eligible);
        workerSelector.verifySelection(selected, localOnly);
        selectionNanos += System.nanoTime() - selectionStarted;

        // The user's prompt is never logged.
        log.info("event=routing_decision sessionId={} dataRoutingPolicy={} localOnly={} layaTier={} requiredTier={} "
                        + "candidateWorkerCount={} selectedWorkerId={} layaLatencyMs={} workerSelectionLatencyMs={} "
                        + "totalRoutingLatencyMs={}",
                request.sessionId(), DataRoutingGuard.policyOf(request),
                localOnly.map(LocalOnlyRequirement::code).orElse("none"), decision.tier().label(), requiredTier.label(),
                request.workers() == null ? 0 : request.workers().size(), selected.workerId(),
                millis(layaNanos), millis(selectionNanos), millis(System.nanoTime() - totalStarted));
        log.debug("event=laya_probabilities sessionId={} probabilities={}", request.sessionId(), decision.probabilities());

        return new RoutingResponse(selected.workerId());
    }

    private static String millis(long nanos) {
        return String.format("%.2f", nanos / 1_000_000.0);
    }
}
