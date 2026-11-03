---
tags: [skill-creator]
max_turns: 20
timeout_seconds: 600
allowed_tools: [Read, Glob, Grep, Skill]
---

i ran `java -jar validator_cli.jar encounter.json -ig hl7.fhir.r4.core#4.0.1` on an R4 Encounter and it complains that Encounter.class is wrong, but the same file validates fine on our R4 server. the ig is R4 so why is it failing? what's the fix
