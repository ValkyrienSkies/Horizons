![Logo](horizons.png)
# Horizons
A rube-goldberg style tech mod built on Valkyrien Skies.

## Development client

Install a full Java 21 JDK and set `JAVA_HOME` to its installation directory.
The Minecraft build tools require the JDK, including `javac` and `lib/ct.sym`;
a Java 21 runtime alone is insufficient. The mod still targets Java 17 bytecode.

From the project directory, launch the Forge development client with:

```sh
./gradlew runClient
```

The first launch downloads Minecraft assets and dependencies. A graphical desktop
session is required to display the client.

![PDA Demo](pda-generic.gif)
