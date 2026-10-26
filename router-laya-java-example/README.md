# router-laya-java-example

> **Experimental.** InfraMesh Router that judges request complexity with
> [laya-java](https://github.com/NandhaKishorM/laya/tree/main/laya-java), the JVM port of Laya.

This is the Java / Spring Boot counterpart of the Python
[`router-laya-example`](../router-laya-example). Same policy, same configuration keys, same
Laya question — but no Python at run time: Laya runs in-process through ONNX Runtime.

laya-java is **`0.1.0-SNAPSHOT` and not on Maven Central yet**, which is why this module is
marked experimental: it builds against a locally installed snapshot (see
[Building laya-java](#building-laya-java)). The Python example stays the reference until
laya-java is released.

## Architecture

```text
InfraMesh Console
        │ RoutingRequest (+ WorkerCandidates)
        ▼
router-laya-java-example  (Spring Boot, port 8094)
        │
        ├── WorkerSelector       RoutingHints hard filters / soft preference
        ├── PromptBuilder        RoutingRequest.messages → last user message
        ├── LayaTierClassifier   laya-java Router → Choice → WorkerTier (small / medium / powerful)
        └── WorkerSelector       capability tier + Load Score → one Worker
        │
        ▼ RoutingResponse { workerId }
```

| Component | Decides |
|---|---|
| `LayaTierClassifier` | What the request *means*: its complexity tier. Nothing else. |
| `WorkerSelector` | Which Worker: capability tier from configuration, then real-time load. |
| Console / Worker | Everything else. |

Laya is asked one Choice question — *"Which worker class is most appropriate for this
request?"* — with `small` / `medium` / `powerful` as options. Only `choice` is used;
confidence is not (the checkpoints ship uncalibrated temperatures), and `probabilities` are
logged at `DEBUG` only. A Laya failure is reported as `502` — Laya is local, so there is no
transient remote failure to fall back from.

laya-java's `Router` picks the checkpoint per request, as the Python `laya.Router` does:
`english` for English text, `multilingual` for everything else. Both are loaded at startup
(`router.laya.preload`), so no request pays a cold load and a missing graph fails startup
rather than the first non-English request. laya-java documents one ONNX session as unsafe
for concurrent `predict` calls, so calls are serialized, as in the Python example.

## Requirements

- JDK 17+ (the build runs on the repository's Gradle 9 / JDK 25 setup)
- Python 3.10+ **once**, to download the checkpoints and export the ONNX graphs
- ~4.3GB of disk for the prepared english + multilingual checkpoints and graphs
- Memory for both resident graphs: their weights alone are ~2.9GB

## Building laya-java

laya-java's source is **not** part of this repository. Clone it anywhere outside it and
install the snapshot into `~/.m2` (the root build already lists `mavenLocal()`):

```bash
git clone https://github.com/NandhaKishorM/laya.git ~/src/laya
cd ~/src/laya/laya-java

# laya-java's wrapper is Gradle 8.10, which does not run on JDK 25. Run it on JDK 17 or 21;
# the classes are compiled for 17 by its toolchain either way.
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew publishToMavenLocal
```

That installs `com.convaiinnovations:laya-java:0.1.0-SNAPSHOT`. The version this module uses
is `layaJavaVersion` in the root `gradle.properties`.

> Until this is done, the root `./gradlew build` fails to resolve laya-java. Remove
> `include 'router-laya-java-example'` from `settings.gradle` if you do not need this module.

## Preparing the checkpoints

laya-java does not download anything: it reads a checkpoint directory and an exported ONNX
graph. Prepare both checkpoints once, with laya's own script, in a virtualenv that is not
this repository's:

```bash
cd ~/src/laya
python3 -m venv .venv-export
.venv-export/bin/pip install 'laya[onnx]==0.4.13'

for c in english multilingual; do
  PYTHONPATH=. .venv-export/bin/python laya-java/scripts/prepare_checkpoint.py --checkpoint $c
done
```

This writes `laya-java/.work/checkpoints/{english,multilingual}` and
`laya-java/.work/onnx/{english,multilingual}/laya.onnx` (+ `laya.onnx.data`), and verifies
each graph against PyTorch. Keep them outside this repository.

## Local Run

```bash
export LAYA_CHECKPOINTS=~/src/laya/laya-java/.work/checkpoints
export LAYA_ONNX_ROOT=~/src/laya/laya-java/.work/onnx

INFRA_NODE_API_KEY=<node api key> ./gradlew :router-laya-java-example:bootRun
```

It listens on port `8094`. Startup loads both checkpoints and runs a warm-up predict before
the Router accepts requests (about 3s on an M3 Max; a routing decision then takes ~75ms of
Laya time). Without `LAYA_CHECKPOINTS` / `LAYA_ONNX_ROOT`, startup fails with a message
saying so.

OUTBOUND mode works as in the other Java examples: `--spring.profiles.active=outbound`
with `INFRAMESH_NODE_ID` / `INFRAMESH_NODE_CREDENTIAL` from Node Registration.

## Configuration

`src/main/resources/application.yml`, under `router.laya` — the same keys as the Python
example's `application.yml`, plus where the model lives:

| Key | Default | Meaning |
|---|---|---|
| `checkpoints-root` | `${LAYA_CHECKPOINTS}` | Holds `english/` and `multilingual/`. Required. |
| `onnx-root` | `${LAYA_ONNX_ROOT}` | Holds `english/laya.onnx` and `multilingual/laya.onnx`. Required. |
| `preload` | `[english, multilingual]` | Checkpoints loaded at startup. |
| `max-loaded` | `2` | Checkpoints resident at once — a memory ceiling. |
| `fallback-policy` | `FALLBACK` | `STRICT`: no capable Worker → 422. `FALLBACK`: use the highest available tier. Env: `ROUTER_LAYA_FALLBACK_POLICY`. |
| `default-tier` | `small` | Tier of an unmapped model, and of a Worker with no models. |
| `model-capabilities` | examples | `pattern` (case-insensitive, `*` / `?` wildcards) → `tier`; first match wins. |
| `weights` | `0.30/0.25/0.20/0.15/0.10` | Load Score weights, same as `router-static-example`; must sum to 1.0. |

Authentication is infra-node's: `infra.node.api-key`, from `INFRA_NODE_API_KEY` (the
committed default is a local development key only).

## Tests

```bash
./gradlew :router-laya-java-example:test
```

runs everything with Laya mocked, and skips `LayaModelIntegrationTest`. With
`LAYA_CHECKPOINTS` and `LAYA_ONNX_ROOT` set, that test runs the real model over English and
Korean prompts.

Checked against the Python example on laya 0.4.13: for the same four prompts, laya-java and
Python `laya.Router` returned the same choice and the same probabilities to four decimal
places.

## Differences from the Python example

- Runtime: in-process ONNX Runtime instead of PyTorch; no Hugging Face download at startup
  — the checkpoints are prepared ahead of time.
- Both checkpoints are loaded at startup (Python loads them lazily on first use).
- Health is infra-node's `HealthCheckController`. Since a failed warm-up fails startup,
  there is no "DOWN until the model is loaded" window to report.
- Error bodies are Spring Boot's default error response.
