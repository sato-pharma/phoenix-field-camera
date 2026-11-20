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
polyclinic) beside the satellites alone, and the last ten minutes of readings are kept. The shutter is always live.
The seal carries the reading; Phoenix reads the 98% radius (Android's own accuracy figure is a 68% radius, 1.85 times narrower).

## 2.1.0

- **The photo is never lost.** The shutter stores the stamped photo and everything known at that instant in the app's
  private folder at once. It is sealed with the most accurate reading within 40 m from the ten minutes before the
  shutter (one within 20 m and nearest in time first). With none, the visit waits as "location pending" and takes the
  first reading within 40 m in the ten minutes after; with none by then, or after the phone restarted, it is sealed
  with no place. The seal names which (`locationStatus`: `locked` within 2 minutes before or 30 seconds after,
  `cached` 2 to 10 minutes before, `late` 30 seconds to 10 minutes after, `none`), and its place fields are empty
  for `none`. A pending visit survives the app being closed. The rep reads only "Saved" or "Location pending"; the
  old "step near a window" refusal is gone. A fake-location app still stops the photo.
- **The four doctor's-name photos.** For a customer with no place in Phoenix yet the rep says what the photo shows:
  the clinic sign or door, the doctor's name on his room door, a building sign or banner with the doctor's name, the
  polyclinic's printed prescription with both names, or the doctor's business card inside the polyclinic. The choice
  is sealed in the visit (`photoKind`).
- **Sending in the background.** Android's own scheduler (WorkManager) sends the waiting visits as soon as the phone
  is connected, with the app closed or after a restart, and looks again every 15 minutes. One quiet "Visits waiting
  to be sent" notification shows while anything is on the phone, and at 22:00 Cairo, once a day, "Connect to the
  network to send today's visits." No count is ever shown.
- **Phoenix refuses nothing from the camera.** Phoenix answers every visit it received with success and the System
  Administrator decides; any other refusal leaves the visit on the phone marked "This visit went to the System
  Administrator." A sealed photo is kept until Phoenix has it, however old.
- **The time anchor** is renewed every time Phoenix answers, once it is five minutes old.
- **Test accounts** sign in and send their visits into Phoenix's test world: the sign-in accepts ids up to 35
  characters (TEST-...), and the seal carries the HR ID Phoenix's own pack names (the rep a test account mirrors),
  never the typed login id. Nothing is sealed before the pack has loaded.

The time on the photo is the satellites' own clock whenever the phone has seen the satellites since it was switched
on, then Phoenix's clock as last read over the network, and only then the phone's clock, which the seal names so
Phoenix can tell. A fake-location mark on any reading, a fake-location app switched on, a rooted phone or an emulator
stop the photo, whichever source the position came from.

The app wears the Phoenix identity: the signature card's navy, orange and cream, Montserrat and Pinyon Script
(both under the SIL Open Font License), square corners and flat colour. The launcher icon is the Phoenix emblem on navy.

The release key is never in this repository. The `build` workflow writes it from the repository secrets, builds
the signed APK and refuses one whose certificate is not the one Phoenix trusts.
