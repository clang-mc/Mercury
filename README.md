# Mercury

Mercury is a Fabric mod for Minecraft 1.21.4 that accelerates `.mcfunction` execution with a custom JIT pipeline. The project focuses on datapack functions, not chat commands or command blocks, and keeps mod compatibility by preserving Brigadier-driven parsing while progressively replacing expensive runtime paths with compiled JVM bytecode.

## What Mercury does

Mercury loads datapack functions into its own IR, rebuilds control-flow and command metadata at reload time, and then chooses between several execution strategies:

- Direct lowering for optimized command subsets such as scoreboard arithmetic, selected `execute` forms, and selected `data modify storage` forms.
- Reflective bridge for unknown but safe leaf commands.
- Action fallback for commands that need vanilla execution semantics.
- Macro-aware prefetch and macro callsite specialization for `function ... with storage`.
- Tier-2 recompilation for hot caller functions, removing Tier-1 profiling hooks and replacing macro callsites with guarded specialized paths.

The mod keeps vanilla semantics as the ground truth. When Mercury cannot prove that a path is safe to specialize, it falls back to the original command execution model rather than guessing.

## Current feature set

Implemented today:

- Parse IR and semantic IR reconstruction for loaded `.mcfunction` files.
- JVM bytecode generation for supported functions.
- Direct static calls between compiled functions.
- Early inlining before bytecode generation.
- Slot promotion for hot scoreboard-heavy regions.
- Specialized lowering for:
  - `execute if score`
  - `execute store result|success score`
  - `execute store result|success storage`
  - `data modify storage ... set|merge value|from storage`
- Macro prefetch candidates and active prefetch lines.
- `FastMacro` remains the synchronous, immediate fallback path via `ExpandedMacro`.
- Async preparation of macro specializations, plus Tier-2 installation for hot callers.
- Debug and dump commands under `/mercury`.

Still intentionally conservative:

- Broad `execute as/at/in/...` source-transform chains.
- General-purpose NBT list caching.
- Arbitrary mod control-flow commands.
- Unlimited re-optimization after Tier-2 installation.

## Mercury JIT architecture

Mercury currently works in three main layers.

### 1. Reload-time analysis

When datapacks reload, Mercury:

- captures function source and parsed command structure
- builds Parse IR and Semantic IR
- resolves direct-lowering opportunities
- constructs lowered units for code generation
- rebuilds macro prefetch candidates and macro optimization state

This happens around the function loading pipeline so analysis can stay global instead of being limited to single-function postprocessing.

### 2. Tier 1 compilation

Tier 1 is the baseline compiled path:

- functions are lowered into Mercury IR
- supported commands become direct bytecode
- unresolved commands become bridge or fallback nodes
- profiling hooks stay enabled
- macro `with storage` callsites remain on the synchronous `ExpandedMacro` path, but now report profile and prefetch information

Tier 1 is responsible for collecting the facts needed for deeper optimization.

### 3. Tier 2 recompilation

Once a caller becomes hot enough, Mercury can perform a second compilation pass:

- runtime profile data is consulted
- macro callsites choose one or more guarded specializations based on macro size and observed value distribution
- specialized paths are compiled and installed at a safe point
- the hot caller is recompiled without Tier-1 profiling hooks
- old Tier-1 artifacts are retired so they can be collected

This second pass is not trying to compete with the JVM JIT on generic low-level optimization. Its purpose is to do domain-specific transforms the JVM cannot infer, such as macro callsite specialization and macro-free fast paths.

## Macro pipeline

Mercury treats macro calls as a first-class optimization target.

- Stage 1: `FastMacro` still synchronously builds or reuses `ExpandedMacro`.
- Prefetch: for stable `storage`-backed macro argument carriers, Mercury can cache argument views.
- Profiling: callsites record hotness and value distributions.
- Specialization: when a callsite becomes hot, Mercury can prepare specialized, macro-free paths for common argument sets.
- Tier 2: hot callers can inline or directly invoke those specializations, guarded by argument checks.

This keeps correctness simple while still making room for aggressive optimization where the data proves it is worthwhile.

## Commands

Mercury currently exposes:

- `/mercury dump`
- `/mercury dump parsed`
- `/mercury dump semantic`
- `/mercury dump prepared`
- `/mercury dump classes`
- `/mercury dump prefetch`
- `/mercury dump macro-optimization`
- `/mercury jit enable`
- `/mercury jit disable`

The dump commands export internal state into the server run directory under `mercury/dumped/`.

## Testing

The project includes an integration test script that boots a development server, uses RCON, runs datapack functions, and validates both results and generated artifacts.

Compile:

```powershell
.\gradlew.bat compileJava --console=plain
```

Run the integration test:

```powershell
python scripts\integration_rcon_test.py
```

The script covers:

- baseline scoreboard execution
- specialized `execute` and `data` lowering
- function inlining
- macro prefetch behavior
- macro specialization dumps
- Tier-2 guarded specialization paths

## Project goals

Mercury is trying to make complex datapack workloads, especially scoreboard-heavy logic and storage-backed macro workflows, behave more like compiled code while staying compatible with Fabric mods and vanilla command semantics.

The long-term direction is:

- more command lowering coverage
- stronger cross-function analysis
- broader macro specialization
- better tiered compilation policies
- tighter memory lifecycle management for generated artifacts

## License

Apache-2.0
