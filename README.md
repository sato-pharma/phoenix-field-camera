# Phoenix Camera

A small Android camera for Sato Pharma's medical reps. It takes a live photo (no gallery), stamps it with the
satellites' time and position, and seals it with a key held in the phone's secure chip. It works with no network.
The rep sends the file to the System Administrator on WhatsApp as a Document; Phoenix checks the seal when the
photo is uploaded on "Validate with my photo" and accepts it for 72 hours after it was taken.

Inside a building (a polyclinic, a pharmacy deep in a mall) the satellites rarely reach. The rep takes the position at
the door or outside, and the app holds that satellite position for 9 minutes while they walk in and take the photo.
The seal records how old the position was at the shutter and how many satellites fixed it, and the photo's stamp
says "Position from the door"; Phoenix accepts a position up to 10 minutes old and shows its age to the Super Admin.
The position never comes from Wi-Fi or the mobile network.

The app wears the Phoenix identity: the signature card's navy, orange and cream, Montserrat and Pinyon Script
(both under the SIL Open Font License), square corners and flat colour. The launcher icon is the Phoenix emblem on navy.

The release key is never in this repository. The `build` workflow writes it from the repository secrets, builds
the signed APK and refuses one whose certificate is not the one Phoenix trusts.
