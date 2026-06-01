# Real garment acceptance images

Drop your own clothing photos here to run the real-image acceptance tests.
The synthetic fixtures in the parent directory are only smoke tests; **the
milestone is only complete when real photos pass through the running Docker
CV service.**

## Naming convention

`test_real_images.py` selects files by filename prefix (case-insensitive):

| Prefix | Expected result |
|--------|-----------------|
| `shirt*` / `top*` / `tshirt*` | exactly one TOP, zero BOTTOM |
| `trousers*` / `pants*` / `jeans*` / `bottom*` | one BOTTOM, zero TOP |
| `outfit*` | both TOP and BOTTOM present |

Accepted extensions: `.jpg`, `.jpeg`, `.png`, `.webp`

Example:
```
tests/fixtures/real/shirt_black.jpeg
tests/fixtures/real/trousers_navy.jpg
tests/fixtures/real/outfit_full.jpg
```

Tests for a category are skipped if no matching image is present, so you can
add them incrementally.

## Running

Inside the running container (model loaded):
```
docker exec vastra-cv pytest tests/test_real_images.py -v
```

These real photos are git-ignored by default (see `.gitignore` here) so you
don't commit personal images. Remove the ignore rule if you want to share a
fixture set with the team.
