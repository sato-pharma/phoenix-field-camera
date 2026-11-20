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

## 2.2.0

- **Add a new customer and validate a customer with no network**, the same way as a visit (owner, 20 Nov 2026:
  "there are bricks that have no network"). Beside Planned and Unplanned the home screen offers "Add new customer"
  and "Validate a customer". With network the camera still sends the rep to the main account.
  - *Validate a customer:* the pack's customers with no place yet, then the photo-kind choice and the camera. No visit
    form. The seal's visit block says `intent: "validate"`, with the customer's id and a fresh `visitId`.
  - *Add new customer:* the main account's form: AM or PM; for PM the first, middle and last name, for AM one name of
    at least 3 letters; the customer type (Clinic by default for PM); the speciality where the type needs one; the
    proposed grade A, B or C; an optional phone. Then the photo-kind choice and the camera. The seal's visit block says
    `intent: "new_customer"` with a phone-made `customerId`, a fresh `visitId` and `newCustomer` holding everything
    typed, so it is all signed. The choices come from the pack's `newCustomerOptions`; on an older Phoenix without
    them the camera keeps sending the rep to the main account.
  - Both take the same never-lost, pending-location and background-send path as a visit, and go for approval in
    Phoenix. A visit's seal now says `intent: "visit"` (a seal without one is a visit). The sync carries a visit form
    for visits only.
  - The rep reads only "Saved", "Location pending" and "Waiting to be sent".
- **The "Update the app" button.** When the pack's `latestApp` names a newer version than the one installed, the home
  screen says "A new version of Phoenix Camera is ready" with one button. Only when the rep presses it, the APK is
  downloaded from the pack's short-lived signed link into the app's cache (a fresh pack is read first where Phoenix
  answers), its size and SHA-256 are checked against the pack (any difference deletes it: "The update did not
  download whole. Try again."), and it is handed to Android's own installer. The first time, Android asks the rep to
  allow "Install unknown apps" for Phoenix Camera; the app opens that setting and continues when he comes back.
  Android refuses an APK signed with another key. Nothing downloads in the background. The update keeps the same
  package and key, so the app's data stays, and the visits waiting to send live in the app's private files folder,
  never in the cache.

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
