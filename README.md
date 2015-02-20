# Chibi SMSC

Chibi's SMSC

## Configuration

See `.chibi_smsc_configuration`

## Building

```
mvn package
```

## Running

```
java -jar <target/jar-file> -c .chibi_smsc_configuration
```

## Sandbox

The sandbox provides a way to enqueue MT jobs using Sidekiq in Ruby for processing in Java

```
cd sandbox
bundle exec foreman run ruby enqueue_mt.rb -e ../.chibi_smsc_configuration
```

This if you java process is running it should execute the job
