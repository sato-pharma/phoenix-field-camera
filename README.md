# Phoenix Camera

A small Android app for Sato Pharma's medical reps: their visit app for a clinic with no network. Since 2.0 the rep
signs in once with his HR ID and Phoenix password, where there is network, and the phone keeps his list, today's plan
and the line's products. With no network he chooses Planned or Unplanned, picks the customer, fills the same visit the
main account asks for (who was on it, the products discussed, the comment), and takes a live photo (no gallery). The
photo is sealed with a key held in the phone's secure chip, together with the place, the time and the visit it was
taken for. The visit waits on the phone and sends itself the moment Phoenix answers again
(`POST /api/phoenix-crm/field-camera/sync`): a customer with no place is validated on the photo, and the visit is
recorded, dated by Phoenix's own clock (a time anchor taken while online, plus the phone's uptime). While Phoenix
answers, the camera refuses a visit and sends the rep to the main account. A new customer is added from the main
account. No reading is shown to the rep (no satellites, accuracy or distance); the seal carries them to Phoenix.

The rep never waits for the location. It starts the moment the app opens, from the phone's combined position
(Google's fused location: Wi-Fi, the mobile network and the satellites together, the only one that answers inside a
polyclinic) beside the satellites alone. The shutter is always live: the photo is taken at once and sealed with the
most accurate reading around its shutter as soon as the phone is 98% sure of the place to within about 37 m, and at
worst 74 m after 20 seconds. Wider than that, the rep is told to step near a window or the door and the photo is not
kept. The seal carries the reading; Phoenix reads the 98% radius (Android's own accuracy figure is a 68% radius, 1.85 times narrower).

The time on the photo is the satellites' own clock whenever the phone has seen the satellites since it was switched
on, then Phoenix's clock as last read over the network, and only then the phone's clock, which the seal names so
Phoenix can tell. A fake-location mark on any reading, a fake-location app switched on, a rooted phone or an emulator
stop the photo, whichever source the position came from.

The app wears the Phoenix identity: the signature card's navy, orange and cream, Montserrat and Pinyon Script
(both under the SIL Open Font License), square corners and flat colour. The launcher icon is the Phoenix emblem on navy.

The release key is never in this repository. The `build` workflow writes it from the repository secrets, builds
the signed APK and refuses one whose certificate is not the one Phoenix trusts.
