# Solace-Quarkus CD

There is a github actions 'Quarkiverse Release' workflow, which will perform the following actions

- Update and commit POMs to be a release version (eg `1.0.1` vs `1.0.1-SNAPSHOT`)
  - Updates the versions within the docs as well
  - The version can be explicitly set, or `auto` will use what is currently on the branch, and can be used for easily publishing incremental patch releases.
- Perform a deploy, skipping tests, to both github packages, and maven central
- Tag, and create a release on github for this commit
- Increment the minor version of all POMs and re-append `-SNAPSHOT`, (eg `1.0.2-SNAPSHOT`)

## Workflows Supported

### Trunk Based (main)

Merge a feature branch to main. This branch should be updated from main and have all tests passing. Once in main, run the release workflow

- Use the `auto` release version if this only requires a patch version bump, otherwise set an appropriate version

### Release branch

In the following depiction of main, trunk based would no longer work if the 1.0 release version need to be maintained with further patch releases

```
[#7fe6b95   Merge Feature branch PR #5] origin/main
[#7a5bde8   [ci skip] prepare for next development iteration ]
[#5ceb311   [ci skip] prepare release 2.0.0] <- Release Quarkiverse action run with releaseVersion=2.0.0
[#5923308   Merge Feature branch PR #4 (Major overhaul)]
[#a46a01a   [ci skip] prepare for next development iteration ]
[#e354653   [ci skip] prepare release 1.0.1]
[#3923407   Merge Feature branch PR #3]
[#d76a91b   [ci skip] prepare for next development iteration ]
[#f85455c   [ci skip] prepare release 1.0.0]
[#7d2115f   init]
```

In this case, `git checkout -b release/1.0 a46a01a && git push` This is the sha of the last commit that belongs in this release branch, in this case, the commit setting the version to `1.0.2-SNAPSHOT`
Now the same release workflow can be run, but with `sourceBranch=release/1.0`, and the 2 CI commits will land on the release branch instead of main
