# Quarkus Solace

[![Version](https://img.shields.io/maven-central/v/com.solace.quarkus/quarkus-solace?logo=apache-maven&style=flat-square)](https://search.maven.org/artifact/com.solace.quarkus/quarkus-solace)

## About Solace Quarkus Extension

Solace Quarkus Extension for integrating with Solace PubSub+ message brokers. The extension provides the ability to publish or consume events from event mesh.

Samples folder has examples on how to use the connector - [solace-connector-sample](https://github.com/SolaceCoEExt/solace-quarkus/tree/main/samples/hello-connector-solace/src/main/java/com/solace/quarkus/samples)

## Documentation

The documentation for this extension should be maintained as part of this repository and it is stored in the docs/ directory.

## Running the extension

```quarkus build``` to build the extension. Please note that docker should be up & running to run the tests during build process

```quarkus build --no-tests``` to build the extension without running tests

```quarkus dev``` run this command in the samples project folder to start the connector.

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) for details on our code of conduct, and the process for submitting pull requests to us.

## License

This project is licensed under the Apache License, Version 2.0. - See the [LICENSE](LICENSE) file for details.

## Resources

For more information try these resources:

- The Solace Developer Portal website at: http://dev.solace.com
- Get a better understanding of [Solace technology](https://solace.dev).
- Check out the [Solace blog](https://solace.com/blog/) for other interesting discussions around Solace technology
- Ask the [Solace community.](https://solace.community)
