# Cloud development environment

This Codespaces configuration provides Node.js 24 and JDK 17 for the REA CLI and Android-related investigation tools.

After the Codespace opens, verify the environment:

```bash
node --version
npm --version
java -version
```

Then install and start REA's guided setup:

```bash
npx rea-agents@latest setup
```

Review the setup plan before approving any agent configuration changes. Installing REA does not automatically connect it to a separate ChatGPT conversation.

For Android APK inspection, follow REA's current Android analysis guide and install only the additional tools required for the selected analysis.
