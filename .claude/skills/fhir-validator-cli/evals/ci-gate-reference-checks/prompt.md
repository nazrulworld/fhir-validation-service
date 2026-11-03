---
tags: [skill-creator]
max_turns: 20
timeout_seconds: 600
allowed_tools: [Read, Glob, Grep, Skill, Write]
---

Write me a github actions step for validating everything in fixtures/*.json against US Core 6.1.0 on R4. It has to fail the build on real errors but not warnings, also fail if a Reference points at something that isn't in fixtures/, and it needs to be reproducible. Our internal terminology server is http://tx.internal:8080/fhir so use that and not tx.fhir.org. Tell me anything that could make it pass when it shouldn't.
