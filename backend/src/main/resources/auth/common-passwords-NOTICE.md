# Common-password list: source and licence

`common-passwords.txt` is the denylist of the registration password policy
(`com.casino.auth.dto.CommonPasswords`, W2-12 in `docs/plans/week-02.md`).

## Source

- **Dataset:** Mark Burnett, "Ten Million Passwords" (xato.net, 9 February 2015),
  <https://web.archive.org/web/20150211131735/https://xato.net/passwords/ten-million-passwords/>.
  The author states: "So I built a data set of ten million usernames and passwords that I am
  releasing to the public domain."
- **Copy used:** `Passwords/Common-Credentials/xato-net-10-million-passwords-dup.txt` from SecLists
  (<https://github.com/danielmiessler/SecLists>): the 755,995 passwords that occur more than once in
  the dataset. Downloaded on 2026-11-04; SHA-256
  `f1747005da8374ce50b4edec1b8e6057cafb2487ac7c49267b76702325e434c8`.
- **Derived list:** entries trimmed and lower-cased, only those of 12 to 72 printable ASCII
  characters (shorter or longer passwords are already rejected by the other rules), duplicates
  removed, sorted: 15,535 entries; SHA-256
  `1627e43e01fee73fcf60cd89930decee244d9102f86a67d9a1142b3f29d09750`. Only passwords are used;
  the dataset's usernames are not.

## Licence

The dataset is in the public domain (author's dedication above). SecLists, from which the copy was
taken, is distributed under the MIT License:

```
MIT License

Copyright (c) 2018 Daniel Miessler

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Regenerating

If the policy limits change, regenerate the list from the same file (Git Bash or any POSIX shell)
and update the entry count and hashes above:

```bash
curl -sSfL https://raw.githubusercontent.com/danielmiessler/SecLists/master/Passwords/Common-Credentials/xato-net-10-million-passwords-dup.txt \
  | tr -d '\r' \
  | LC_ALL=C awk '{ gsub(/^[ \t]+|[ \t]+$/, ""); $0 = tolower($0) } /^[ -~]{12,72}$/' \
  | LC_ALL=C sort -u > backend/src/main/resources/auth/common-passwords.txt
```

`CommonPasswordsTest` checks that every entry still fits the policy's limits.
