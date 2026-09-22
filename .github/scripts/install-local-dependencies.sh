#!/usr/bin/env bash
set -euo pipefail
# Run from the repository root after downloading the pinned JARs.
# Hash-qualified versions prevent different private JARs sharing a Maven cache key.
sha256sum --check .github/dependencies.sha256

mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/ItemsAdder_4.0.18.jar" -DgroupId="local" -DartifactId="ItemsAdder" \
    -Dversion="4.0.18-tfmc-5a01b37bd744" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/MMOCore-1.13.1.jar" -DgroupId="local" -DartifactId="MMOCore" \
    -Dversion="1.13.1-tfmc-14850d745437" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/MMOItems-6.10.1-20250521.175300-22.jar" -DgroupId="local" -DartifactId="MMOItems" \
    -Dversion="6.10.1-tfmc-8ff714bd3f48" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/MythicLib-dist-1.7.1.jar" -DgroupId="local" -DartifactId="MythicLib" \
    -Dversion="1.7.1-tfmc-a3f86a50d382" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/ProtocolLib.jar" -DgroupId="local" -DartifactId="ProtocolLib" \
    -Dversion="1.0-tfmc-ee2e7ab9b538" -Dpackaging=jar -DgeneratePom=true "$@"
mvn -B --no-transfer-progress org.apache.maven.plugins:maven-install-plugin:3.1.4:install-file \
    -Dfile="libs/MythicMobs-5.8.0-SNAPSHOT.jar" -DgroupId="local" -DartifactId="MythicMobs" \
    -Dversion="5.8.0-SNAPSHOT-tfmc-575aa30aee8e" -Dpackaging=jar -DgeneratePom=true "$@"
