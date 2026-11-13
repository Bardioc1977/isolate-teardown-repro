# Isolate teardown crash reproducer

Small standalone program that creates many short-lived polyglot contexts, each in its own isolate
(`SandboxPolicy.ISOLATED`, implicit engine), runs a script that triggers background compilation and closes
the context with `close(true)`. With GraalVM polyglot / GraalJS `25.5.5.0.3` (community isolates from Maven Central)
the host JVM ends with SIGSEGV in a `TruffleCompilerThread`, or aborts with `pthread_mutex_unlock returned 22`.

Only public artifacts are used: `org.graalvm.polyglot:polyglot`, `js-community` and
`js-isolate-linux-{amd64,aarch64}-community`, all version `25.5.5.0.3`.

## Run

Requirements: JDK 21 or newer (tested with Temurin 25.0.4.1 and 25.0.5), Maven, Linux amd64 or aarch64.

```sh
./run.sh 20 3 1000 work same
```

Arguments: number of JVM runs, JVMs in parallel, contexts per JVM, scenario, close mode, optional extra JVM options.

* scenario `work` (builds, filters, maps, sorts 2000 rows; compilation is triggered), `hot` (numeric loop),
  `trivial` (constant, no compilation: never crashed)
* close mode `same` (worker closes), `other` (another thread closes), `cancel` (another thread closes while the script runs)
* `-Dopt.engine.CompilerThreads=1` as extra JVM option adds a context option

The script prints `runs=N abnormal=K`; `K` is the number of JVMs with a non-zero exit code. `hs_err` files and logs are
written to `target/out-*`. The crash rate depends on CPU load (parallel JVMs, CPU quota): use at least 3 parallel JVMs
on 4 CPUs.

## Observed

Container with 4 CPUs, 3 JVMs in parallel, 1000 contexts per JVM, `-Xmx256m`, scenario `work`:

| platform | close mode | JVMs that crashed |
|---|---|---|
| Linux x86-64, Temurin 25.0.5+8 | `same` | 28 of 60 (13 of 30 with this project's compiled class) |
| Linux aarch64, Temurin 25.0.5+8 | `same` | 47 of 60 |
| Linux aarch64, Temurin 25.0.5+8 | `other` | 55 of 60 |
| Linux aarch64, Temurin 25.0.4.1 | `same` | 9 of 9 |
| Linux aarch64, scenario `trivial` | `same` | 0 of 60 |

## Proposed fixes

`proposal-A.diff` and `proposal-B.diff` are two alternative patches against `oracle/graal` tag `vm-25.5.5.0.3`
(`git apply` from the repository root). With either of them the crash did not occur in my runs; see the comment on
oracle/graal#14962 for the analysis and the numbers. Neither patch was reviewed by anyone at Oracle.
