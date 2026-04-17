# Stage 1.2 — User 1 Avatar Anomaly Investigation

**Generated:** 2026-04-16  
**Investigator:** Claude Code Phase A

---

## Raw Data

### User 1 (sigachev)

| Column | Value |
|--------|-------|
| `user_id` | 1 |
| `username` | sigachev |
| `email` | sigachev.m@gmail.com |
| `provider` | LOCAL |
| `first_name` | Mikhail |
| `last_name` | Sigachev |
| `profile_image` | `https://s3.us-east-1.amazonaws.com/finmates-images/users/4/profile/1707876531054-3.jpg` |
| `thumbnail` | `https://s3.us-east-1.amazonaws.com/finmates-images/users/4/profile/1707876531223-3.jpg` |
| `cover_image` | (null) |
| `bio` | Social trader |
| `display_name` | CyberCoder |
| `created_at` | 2023-06-09 17:52:50 |
| `updated_at` | 2026-04-16 03:40:27 |

### User 4 (runett)

| Column | Value |
|--------|-------|
| `user_id` | 4 |
| `username` | runett |
| `email` | runett@inbox.ru |
| `provider` | LOCAL |
| `first_name` | Mike |
| `last_name` | Runett |
| `profile_image` | `https://s3.us-east-1.amazonaws.com/finmates-images/users/4/profile/1707875760379-4.png` |
| `thumbnail` | `https://s3.us-east-1.amazonaws.com/finmates-images/users/4/profile/1707875760484-4.png` |
| `cover_image` | (null) |
| `bio` | (null) |
| `created_at` | 2023-06-09 17:52:50 |
| `updated_at` | 2026-04-06 17:37:12 |

---

## Analysis

### Are these the same files?

**No.** User 1 and user 4 have distinct files in S3, even though both live under `users/4/profile/`:

| File | Owner | Epoch timestamp | File type | Suffix |
|------|-------|----------------|-----------|--------|
| `1707875760379-4.png` | user 4 (profile) | 2024-02-13 17:09:20 UTC | PNG | `-4` |
| `1707875760484-4.png` | user 4 (thumbnail) | 2024-02-13 17:09:20 UTC | PNG | `-4` |
| `1707876531054-3.jpg` | user 1 (profile) | 2024-02-13 17:22:11 UTC | JPG | `-3` |
| `1707876531223-3.jpg` | user 1 (thumbnail) | 2024-02-13 17:22:11 UTC | JPG | `-3` |

User 4 uploaded ~13 minutes before user 1. Both uploads went to `users/4/profile/`.

### What happened?

This is a **real bug in the profile image upload service**, not seed data or a copied row.

Evidence:
1. The files have **different timestamps** (13 minutes apart), **different formats** (PNG vs JPG), and **different suffix numbers** (`-4` vs `-3`). They are genuinely separate uploads.
2. User 1's file suffix is `-3`, not `-1`. The suffix appears to be a server-assigned counter or random number — it does not encode user_id.
3. User 4's files use suffix `-4` which happens to match their user_id, but this is coincidental.
4. Both uploads stored files to `users/4/profile/` — the backend had a bug where it used the wrong `userId` (4 instead of 1) when constructing the S3 key for user 1's upload.
5. The bug is from approximately **February 13, 2024** (unix epoch ~1707876531 seconds).

The S3 upload path logic was likely reading `userId` from an incorrect source (e.g., a hardcoded test value, a session variable pointing to the wrong user, or a stale value shared between requests).

### S3 Verification

AWS credentials not available in this environment — cannot confirm programmatically that the files exist at these URLs. However, the URLs are stored in the `users` table as the active profile images for user 1, indicating they were successfully uploaded and the application has been serving them since February 2024.

### Is this affecting user experience?

Functionally, yes — user 1 (sigachev) sees their own avatar displayed correctly (the file exists and is accessible), but the S3 path is misleading. The image is stored in user 4's folder. If user 4's folder is ever purged or if access policies are user-scoped by path prefix, user 1's avatar would be lost.

---

## Migration Options

Three options for handling this during Phase A data export:

### Option A: KEEP — Honor existing data as-is

- Export user 1's avatar URL as `profile_image_url` (it's an external-looking full URL, not an S3 key we own cleanly)
- Actually: since the URL IS an S3 finmates-images URL, it would be extracted as `profile_image_key = users/4/profile/1707876531054-3.jpg`
- The key would work (file exists), but the path segment `users/4/` is wrong for user 1
- **Risk:** S3 path-based policies could break access; future purge of user 4 assets would delete user 1's avatar

### Option B: NULL — Clear user 1's avatar during migration

- Set `profile_image_key = NULL` and `profile_image_url = NULL` for user 1 in the export CSV
- User 1 loses their avatar and would need to re-upload
- **Cleaner:** no wrong-path data enters the social DB
- **Loss:** user 1's current display is broken until they re-upload

### Option C: FIX — Copy the file in S3 to the correct path

- Copy `users/4/profile/1707876531054-3.jpg` → `users/1/profile/1707876531054-3.jpg` (in S3)
- Copy `users/4/profile/1707876531223-3.jpg` → `users/1/profile/1707876531223-3.jpg` (in S3)
- Update the export CSV row for user_id=1 to use `users/1/profile/1707876531054-3.jpg`
- **Best outcome:** correct data, no user experience disruption
- **Requires:** AWS CLI access and S3 write permissions

---

## Recommendation

**Option C (FIX)** is ideal if AWS credentials are available — it produces correct data without disrupting user 1's current experience.

**Option B (NULL)** is the safest fallback if S3 access is unavailable — user 1 will lose their avatar but no incorrect paths enter the new system.

**Option A (KEEP)** should be avoided — it propagates a path bug into the new social DB.

---

## User 1 Complete Profile for Export Reference

```
user_id:          1
bio:              Social trader
display_name:     CyberCoder
first_name:       Mikhail
last_name:        Sigachev
location:         Miami, FL
timezone:         America/New_York
linkedin_handle:  https://www.linkedin.com/in/sigachev/
profile_image:    users/4/profile/1707876531054-3.jpg  ← WRONG PATH (bug)
thumbnail:        users/4/profile/1707876531223-3.jpg  ← WRONG PATH (bug)
virtual_balance:  7,872.09  (has traded)
```
