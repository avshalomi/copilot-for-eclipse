# Repository Guidelines

## Project Structure & Module Organization

This is a multi-module Eclipse/OSGi plugin built with Maven/Tycho. Bundles use the `com.microsoft.copilot.eclipse.*` prefix:

- `core`: authentication, LSP integration, and completion/chat logic; `core/copilot-agent/` contains the Node.js agent setup.
- `ui`, `ui.jobs`, and terminal bundles: SWT interfaces, Jobs view, and terminal integration.
- `core.agent.*`: platform-specific agent bundles.
- `feature` and `repository`: Eclipse feature and P2 update-site packaging.
- `*.test`: dedicated test bundles, with Java sources under `src/`.

Production Java also lives under each bundle's `src/`; UI assets include `ui/icons/` and `ui/css/`. See `docs/`, `test-plans/`, and `launch/` for documentation, manual scenarios, and Eclipse launch configurations.

## Build, Test, and Development Commands

Install Node.js 22.13+ and an appropriate JDK; `CONTRIBUTING.md` documents prerequisites, while the root POM specifies a JavaSE-21 target execution environment. Commands below run from the repository root:

- `npm --prefix com.microsoft.copilot.eclipse.core/copilot-agent i -f`: install and populate agent bundles before building.
- `./mvnw clean package`: compile and package plugins.
- `./mvnw test`: run automated tests.
- `./mvnw checkstyle:check`: check code style.
- `./mvnw clean verify`: run full build verification, including Checkstyle; generate the update site under `com.microsoft.copilot.eclipse.repository/target/repository/`.

On Windows PowerShell, use `.\mvnw.cmd`. For interactive development, import the modules into Eclipse, activate the appropriate `*.target` platform, and use `launch/plugin_debug_configuration.launch`.

## Coding Style & Naming Conventions

Follow customized Google Java Style in `checkstyle.xml`: two-space indentation and the supplied 120-column Eclipse formatter profile in `.settings/`. Use UpperCamelCase classes and lowerCamelCase methods/fields. Externalize user-facing strings through Eclipse NLS. Keep blocking work off the SWT UI thread, dispatch widget updates through `Display.asyncExec()`, and dispose resources and listeners.

## Testing Guidelines

Use JUnit 5 in the corresponding test bundle. Name classes `<ClassName>Test` or `<ClassName>Tests` and methods `testMethodName_scenario_expectedOutcome`. Cover changed behavior and clean up resources during teardown. No numeric coverage threshold is configured. SWTBot probes live in `com.microsoft.copilot.eclipse.swtbot.test/probe-scripts/` and require explicit `probe.script` selection; they are skipped by default.

## Commit & Pull Request Guidelines

Recent commits use short imperative subjects, such as `Fix workspace folder URI resolution`; dependency updates use `build(deps): ...`. Branch from `main`. Explain the change and motivation, link relevant issues, and provide validation results. Run build, style, and test checks before submission. Follow `CONTRIBUTING.md`, complete the CLA check when requested, and report vulnerabilities through `SECURITY.md`.
