# Phoenix Camera

A small Android camera for Sato Pharma's medical reps. It takes a live photo (no gallery), stamps it with the
rep's position and the time, and seals it with a key held in the phone's secure chip. The rep sends the file to the
System Administrator on WhatsApp as a Document; Phoenix checks the seal when the photo is uploaded on "Validate with
my photo" and accepts it for 72 hours after it was taken.

The rep never waits for the location. It starts the moment the app opens, from the phone's combined position
(Google's fused location: Wi-Fi, the mobile network and the satellites together, the only one that answers inside a
polyclinic) beside the satellites alone. The shutter is always live: the photo is taken at once and sealed with the
most accurate reading around its shutter as soon as the phone is 98% sure of the place to within about 37 m, and at
worst 74 m after 20 seconds. Wider than that, the rep is told to step near a window or the door and the photo is not
kept. The stamp prints the 98% radius (Android's own accuracy figure is a 68% radius, 1.85 times narrower).

The time on the photo is the satellites' own clock whenever the phone has seen the satellites since it was switched
on, then Phoenix's clock as last read over the network, and only then the phone's clock, which the seal names so
Phoenix can tell. A fake-location mark on any reading, a fake-location app switched on, a rooted phone or an emulator
stop the photo, whichever source the position came from.

The app wears the Phoenix identity: the signature card's navy, orange and cream, Montserrat and Pinyon Script
(both under the SIL Open Font License), square corners and flat colour. The launcher icon is the Phoenix emblem on navy.

The release key is never in this repository. The `build` workflow writes it from the repository secrets, builds
the signed APK and refuses one whose certificate is not the one Phoenix trusts.
