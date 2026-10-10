# External job ingestion

An administrator can import supplied ITviec or TopCV detail URLs. Ingestion runs synchronously on request; it does not start automatically, scrape search results, or bypass source challenges. Pages must provide a single [schema.org JobPosting](https://schema.org/JobPosting) in JSON-LD. JSON-LD arrays, `@graph`, and array-valued `@type` are supported.

```http
POST /api/v1/admin/jobs/crawl
Authorization: Bearer <admin-access-token>
Content-Type: application/json

{
  "sourceSite": "ITVIEC",
  "keyword": "Java",
  "roleCategory": "Backend",
  "location": "Ho Chi Minh",
  "urls": ["https://itviec.com/it-jobs/<actual-job-slug>"]
}
```

`sourceSite` is `ITVIEC` or `TOPCV`; `keyword` labels the run and does not trigger a source search. `roleCategory` is the administrator's category for the imported jobs. `location` is optional and filters the location extracted from the source with an accent-insensitive substring comparison; a missing or nonmatching source location is skipped. This comparison does not translate location names or abbreviations. Supply at most 20 detail URLs. Repeated canonical URLs are fetched once per run.

Only HTTPS on `itviec.com`, `www.itviec.com`, `topcv.vn`, and `www.topcv.vn` is fetched. The host must match the selected source. Credentials, explicit ports, and redirects to other hosts are rejected before fetching their target. Fetches use [Jsoup](https://jsoup.org/apidocs/org/jsoup/Connection), a five-second timeout per connection, a 2 MiB body limit, and at most three manually checked redirects. A run can take longer than five seconds because URLs are fetched sequentially. HTML is parsed locally; no AI call occurs during ingestion.

Imported fields include the job title, description, organization name, job locations, base salary when provided, and posting date. A date-only `validThrough` remains valid through that day in `Asia/Ho_Chi_Minh`; elapsed expiry causes existing active rows for that source URL to become `EXPIRED` and their embeddings to become noncurrent. No expiry date is invented when the source omits it. No skills are inferred during this import, so `imported_skills` remains zero. Imported jobs await an explicit `POST /api/v1/admin/jobs/{id}/index` call to generate embeddings.

Descriptions are normalized to text for matching and deduplication, and the source description is preserved as HTML. The content hash combines source, canonical URL, title, and normalized description. Identical active jobs produce a `DUPLICATE` item. A changed title or description on the same canonical URL replaces earlier active rows: those rows become `REMOVED`, and their embeddings become noncurrent within the import transaction. An identical inactive row requires an explicit administrator status update; importing does not reactivate it.

The response contains the persisted `market_crawl_runs` row and its `market_crawl_items`. Inspect them later with `GET /api/v1/admin/jobs/crawl?limit=20&offset=0` and `GET /api/v1/admin/jobs/crawl/{id}`. Both endpoints require an administrator.

Item outcomes are `IMPORTED`, `DUPLICATE`, `EXPIRED`, `WRONG_REGION`, `REJECTED`, `FETCH_FAILED`, or `IMPORT_FAILED`. A failed item does not roll back other imported jobs. Run status is `COMPLETED` when every item was imported or duplicated, `PARTIAL` when successful and rejected items coexist, and `FAILED` when every item was rejected or skipped. `fetched_urls` counts supplied URL fetches that yielded at least one HTTP response, including rejected responses; redirects do not increment it separately. Other counters report parsed postings, imports, duplicates, region skips, rejected items, and supplied detail pages checked. Search and discovery counters remain zero because this endpoint processes supplied URLs.

An HTTP error, blocked page, missing JSON-LD, ambiguous page with multiple jobs, or invalid metadata produces a visible item outcome without an invented job. For a supported source page without JSON-LD, use `POST /api/v1/admin/jobs` to provide the job fields manually. Tests use a local HTML fixture and mocked fetch responses and never contact job websites.
