# Note to agents: keep environment changes project-scoped

**Audience:** any coding agent working on this machine · **Scope:** all projects, not just this one

## The rule

**Never change machine-global state to satisfy one project.** Not `JAVA_HOME`, not `PATH`,
not `git config --global`, not a global package install, not the system interpreter.

This machine hosts projects pinned to different toolchain versions — at time of writing,
JDK 17, 21 and 25 are all installed and all in use. A global change fixes the project in
front of you and silently breaks the others. The breakage surfaces later, in a build you are
not looking at, with nothing connecting it to what you did.

## Order of preference

Work down this list and stop at the first option that works.

1. **Editor/workspace-scoped settings** — applies only while working in that folder.
   VS Code: `.vscode/settings.json` (usually gitignored, so it stays personal).
2. **A project config file the tool itself reads** — `.nvmrc`, `global.json`, the `toolchain`
   directive in `go.mod`, a project `.python-version`. These travel with the repo and work
   for teammates and CI too.
3. **Per-invocation prefix** — `$env:JAVA_HOME = "..."; mvn.cmd ...`. Ugly but harmless, and
   the right answer for a one-off.
4. **A wrapper script committed to the repo** — when the incantation is long enough that
   people will get it wrong.
5. **Global — only after asking**, and only when you have stated what else on the machine it
   affects. Assume the answer is no.

## Recipes

| Ecosystem | Project-scoped way | Do **not** |
|---|---|---|
| Java / Maven | `.vscode/settings.json` → `terminal.integrated.env.windows.JAVA_HOME` + `java.configuration.runtimes`; or prefix the invocation | Change user/system `JAVA_HOME` |
| Node | `.nvmrc`, or Volta's pin in `package.json`; `npx` for one-offs; tools as `devDependencies` | `npm install -g` |
| Python | A venv inside the project | `pip install --user`, or touching the system interpreter |
| .NET | `global.json` pinning the SDK | Uninstall/replace the global SDK |
| Go | `toolchain` in `go.mod` | Swap the global Go install |
| Git | Repo-local `git config <key> <value>` | `git config --global` |

## Check this before reaching for a global change

**Do CI and the container build already use the right version?** They usually do. If so, the
problem is *local-only*, and a fix that changes shared build configuration is the wrong shape
— you would be adding risk to the release path to solve one person's terminal ergonomics.

### Worked example: rejecting Maven toolchains

In `FinanceAgentFramework`, the local `JAVA_HOME` pointed at JDK 17 while the project targets
release 21, so `mvn` failed. `maven-toolchains-plugin` looked like the clean fix — the pom
declares the JDK it needs, no global variable.

Checking first showed CI (`setup-java` with 21) and the Docker build
(`maven:3.9-eclipse-temurin-21-jammy`) **already run on 21**. The plugin would have bought
nothing there, but it *fails the build* when no matching toolchain is found — so a
`toolchains.xml` would have to be shipped into the Docker image and generated in CI, adding
two new ways for the release pipeline to break in order to fix a local annoyance.

The workspace-scoped setting solved it with no shared-config change at all. **The general
lesson: check where the problem actually lives before changing something everyone depends
on.**

## Leaving no trace

Before finishing, confirm you have not changed anything outside the project:

```powershell
[Environment]::GetEnvironmentVariable('JAVA_HOME','User')     # and 'Machine'
git config --global --list
npm ls -g --depth=0
```

If you did change something global — because you were asked to — say so explicitly in your
final message, with the old value, so it can be reverted.

## A related trap: two toolchains, one output directory

Same root cause, different symptom, and it cost real debugging time on this machine:

The VS Code Java extension compiles into the **same `target/classes`** Maven uses. When it
writes a class file newer than the source, Maven's incremental compiler skips that file
entirely and prints `BUILD SUCCESS` having compiled nothing. If the IDE's compiler had
recorded errors, they sit in the class file and are thrown at runtime instead — so the build
is green and the program fails.

Two habits that prevent it:

- Point the IDE and the build tool at the **same** JDK (`java.configuration.runtimes` with
  `"default": true`).
- **Verify the artifact, not the exit code.** A Maven log with no `Compiling N source files`
  line compiled nothing. To force a real build, delete the class files first; to confirm what
  is actually there, inspect it (`javap -p -cp target/classes <class>`).

The wider habit: an exit code, a status column and a byte count are all claims *about* the
work. When it is cheap, check the work itself.

---

*To make this apply automatically to every project on this machine, copy it to
`~/.claude/CLAUDE.md` — Claude Code loads that file in every session regardless of which
repo is open. Keeping it as a standalone note instead means remembering to hand it over.*
