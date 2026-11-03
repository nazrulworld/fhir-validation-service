---
tags: [skill-creator]
max_turns: 20
timeout_seconds: 600
allowed_tools: [Read, Glob, Grep, Skill]
---

We start the validator like this in docker: `java -jar validator_cli.jar server 8080 -version 4.0.1 -ig hl7.fhir.us.core#6.1.0 -profile http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient -level errors`. Then we POST patients to /validateResource from our app, and they come back with no US Core errors even for patients missing required fields, plus warnings still show up. Also it's on an internal network with -allowNetworkAccess, is that ok? And can I use the `client` subcommand for our XML files?
