# API Design

---

## 29. Structured error responses — machine-readable errors for API consumers

The original error handling returned ad-hoc `Map<String, String>`:

```kotlin
ResponseEntity.badRequest().body(mapOf("error" to e.message))
```

This forces API consumers to parse arbitrary strings to understand what went wrong. No
error codes, no field-level detail, no machine-readable categorization.

**The fix:** A standard `ApiError` class + global `@ControllerAdvice`:

```kotlin
data class ApiError(
    val type: String,      // "invalid_request_error", "conflict_error", "api_error"
    val code: String,      // "amount_invalid", "currency_invalid", "state_conflict"
    val message: String,   // Human-readable description
    val param: String?     // Which field caused the error (for validation errors)
)
```

The `@ControllerAdvice` handles all exception types consistently:
- `IllegalArgumentException` → 400 + `invalid_request_error`
- `IllegalStateException` → 409 + `conflict_error`
- `MethodArgumentNotValidException` → 400 + field-level error from Bean Validation
- `Exception` → 500 + generic `api_error` (with full stacktrace logged server-side)

Per-controller `@ExceptionHandler` methods were removed — all error handling flows through
one place.

**Takeaway:** Error responses are part of your API contract. Define them as structured
objects early. Adding `@ControllerAdvice` takes 30 minutes and saves every API consumer
from guessing at error formats.

---

## 48. Cursor-based pagination is O(1) — offset pagination is O(n)

Spring's `Pageable` with `@PageableDefault` generates `OFFSET/LIMIT` SQL. For page N with
size 20, PostgreSQL must scan and discard N*20 rows before returning the requested 20. At
page 5000 of a 100M-row table, that's 100K rows scanned per request — and it gets linearly
worse with depth.

**Fix:** Added keyset (cursor-based) pagination using `(created_at, id)` as the cursor:

```sql
WHERE (created_at < :cursorTime OR (created_at = :cursorTime AND id < :cursorId))
ORDER BY created_at DESC, id DESC
LIMIT :pageSize
```

This uses the index `(created_at DESC, id DESC)` for a direct seek — O(1) regardless of
how deep into the result set you are. The client passes `starting_after={last_id}` to get
the next page.

The existing offset-based endpoints are kept for backward compatibility, but the new
`/v1/payment_intents/cursor` endpoints should be preferred for any integration that
paginates large result sets.

**Takeaway:** Offset pagination is a scalability trap that looks fine at 1K rows and
breaks at 1M. Add cursor-based pagination before you need it — retrofitting pagination
in a live API is painful.
