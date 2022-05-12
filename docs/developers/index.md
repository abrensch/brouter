---
title: Developers
has_children: true
nav_order: 4
---

# Developer Information

## Profile Development

BRouter offers [freely configurable routing profiles](../features/costfunctions.md).
To extend existing profiles or develop
you own profile see [Profile Developers Guide](profile_developers_guide.md) for
a technical reference.

### (Optional) Generate profile variants

This repository holds examples of BRouter profiles for many different
transportation modes. Most of these can be easily customized by setting
variables in the first `global` context of the profiles files.

An helper script is available in `misc/scripts/generate_profile_variants.sh`
to help you quickly generate variants based on the default profiles, to create
a default set of profiles covering most of the basic use cases.
