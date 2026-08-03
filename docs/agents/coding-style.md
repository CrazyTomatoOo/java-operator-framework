# Coding style: Java

Applies to all Java sources under `operator/` and `example/`.

## Javadoc

1. Every `public` and `protected` class, method, and field MUST have a Javadoc comment. Private/package-private members are exempt.
2. Use `@param`, `@return`, and `@throws` (a.k.a. `@throw`) according to the actual signature:
   - `@param` for every parameter, with a short description.
   - `@return` whenever the method returns a value (non-void).
   - `@throws` for every exception that can propagate (checked exceptions always; unchecked ones when callers are expected to handle them).

## Inline comments

Inline/within-line comments — explanatory comments inside method bodies or trailing at the end of a code line — MUST use `//`.

- `/** ... */` is reserved for Javadoc on a declaration (class, interface, enum, record, method, constructor, field); never use it as an inline or trailing comment.
- `/* ... */` is used only for the file copyright header; do not use it for explanatory inline comments.

## Import order

Imports are grouped in the following order, with exactly one blank line between groups:

1. `android.*` — Android platform
2. `com.huawei.*` / other Huawei packages — Huawei company
3. Other commercial organizations (e.g. `com.*` from vendors)
4. Other open-source third parties (e.g. `com.google.*`, `com.squareup.*`)
5. `net.*` / `org.*` open-source organizations
6. `java.*` / `javax.*` — JDK, always last
