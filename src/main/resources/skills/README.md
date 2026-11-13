# Local Skill Catalog

Runtime reads catalog.json and imported-catalog.json once. Source data and typed learning graph are local;
no extraction-time network or generative model calls are made. See ../../../docs/skill-catalog-roadmap.vi.md.

The base catalog is independent EduNexus curation, not copied roadmap.sh content. The imported resource is empty
until authorized data is reviewed and imported. Never label synthetic fixtures as imported upstream content.

Skill fields: stable id, display name, domain, type, aliases, ambiguousAliases, contextPattern,
recognitionPatterns, relatedSkills, learningTopics, sourceReferences. Context patterns use folded lower-case text.
Types CATEGORY and LEARNING_TOPIC are never automatically matched as job skills. Relations never imply aliases.

Topic fields: stable id, title, type, prerequisites, sourceReferences. Only explicit source-supported prerequisites
may be used. Do not infer graph semantics from positioning, drawing order, or an unlabeled connector.

Every source needs revision, license and permission evidence. The importer accepts reviewed local JSON graph
or topic-slug@node-id.md files, and rejects roadmap.sh content without documented written permission.
Stage imports under target, review output and run Java loader tests before replacing imported-catalog.json.

Catalog version includes SHA-256 of both resource files. Rebuild/restart after resource changes; snapshots with
older versions are reprocessed safely using the documented offline maintenance tool. Do not edit old source hashes.
