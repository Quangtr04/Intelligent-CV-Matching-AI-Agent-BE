# Java coding conventions

Apply these conventions to every Java file created or edited, including tests and Java development tools.

- Preserve existing behavior, API contracts, validation, transactions and database operations when formatting.
- Indent with four spaces. Use UTF-8 and LF line endings, with a final newline.
- Keep lines within 120 characters where Java syntax permits. Split long literal strings into compile-time literal concatenations without changing their decoded contents.
- Put declaration annotations on their own lines and parameter annotations above their parameter. Split multiple type-use annotations onto separate lines while preserving their exact targets.
- When a record, constructor or method has multiple parameters, put each parameter on its own line.
- Place the opening brace on the declaration line. Expand method bodies and statements onto separate lines.
- Keep one blank line between methods, important members and logical groups. Avoid compressed one-line control flow.
- Keep the layer / role / feature packages and separate request / response DTO structure.

The shared formatter profile is `config/java-formatter.xml`. Format all main, test and development Java sources with `mvn formatter:format`; validate with `mvn formatter:validate`. `mvn verify` also checks formatting.

The formatter currently combines annotations inside a generic type argument. For multiple element annotations, use the narrow `@formatter:off` / `@formatter:on` layout in `CrawlRequest` and keep each annotation on a separate line. Keep the protected region limited to the annotated type; never move element constraints onto the collection itself.

After regenerating entities, run the formatter before building. If long literal strings require wrapping, run `java scripts/dev/WrapJavaStrings.java apply`, then run the formatter again. Verify that string contents and logic remain unchanged.
