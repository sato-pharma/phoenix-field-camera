# Phoenix Camera

A small Android camera for Sato Pharma's medical reps. It takes a live photo (no gallery), stamps it with the
satellites' time and position, and seals it with a key held in the phone's secure chip. It works with no network.
The rep sends the file to the System Administrator on WhatsApp as a Document; Phoenix checks the seal when the
photo is uploaded on "Validate with my photo" and accepts it for 72 hours after it was taken.

The release key is never in this repository. The `build` workflow writes it from the repository secrets, builds
the signed APK and refuses one whose certificate is not the one Phoenix trusts.
