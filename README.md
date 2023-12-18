# Quarkus Solace

[![Version](https://img.shields.io/maven-central/v/io.quarkiverse.solace/quarkus-solace?logo=apache-maven&style=flat-square)](https://search.maven.org/artifact/io.quarkiverse.solace/quarkus-solace)

## About Solace Quarkus Extension

Solace Quarkus Extension for integrating with Solace PubSub+ message brokers. The extension provides the ability to publish or consume events from event mesh.

Samples folder has examples on how to use the connector - [solace-connector-sample](https://github.com/SolaceCoEExt/solace-quarkus/tree/main/samples/hello-connector-solace/src/main/java/io/quarkiverse/solace/samples)

## Documentation

The documentation for this extension should be maintained as part of this repository and it is stored in the `docs/` directory.

## Running the extension

```quarkus build``` to build the extension. Please note that docker should be up & running to run the tests during build process

```quarkus build --no-tests``` to build the extension without running tests

```quarkus dev``` run this command in the samples project folder to start the connector.
