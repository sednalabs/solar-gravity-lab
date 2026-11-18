# Flying in Solar Gravity Lab

The flight deck uses the same native world as the laboratory. Camera movement
changes your view; controls change the spacecraft. An engine-off spacecraft
continues moving under gravity, and releasing a rotation control does not erase
its existing spin.

This guide describes the flight campaign source. The [release page](https://github.com/sednalabs/solar-gravity-lab/releases)
identifies installable Android builds; source and hosted tests do not establish
physical Galaxy installation. The [desktop guide](../clients/desktop/README.md)
gives the native desktop launch command.

## A first deliberate burn

Start orbital flight and press **Mark**. Mark cuts the controls, pauses time and
records a checkpoint. **Reset** returns to that physical state on a new timeline,
retaining the original experiment history.

Choose **Prograde**, then run at **1×** with zero throttle. Watch the direction
marker and alignment angle as the nose turns. A solid marker is ahead of the
nose; a hollow marker at the edge is behind it. **Stabilise** damps spin but does
not continuously track prograde.

Open **Plan** on Android or **Preview a burn** on desktop. Both pause the flight.
Choose a short duration and modest throttle, then calculate. Compare periapsis
and apoapsis altitudes before and after, along with fuel use. The preview starts
from the real attitude and spin: thrust begins while the controller turns. It
does not silently wait for alignment. A world change makes an old result stale.

Close the planner, run at 1×, and ease up the throttle. Watch fuel flow and the
changing orbit. Press **CUT** to end the burn. A preview does not execute a
manoeuvre for you. Use Reset to compare another burn from the same marked state.

## Read the orbit, not just the altitude

Altitude is where you are now. Periapsis is the lowest altitude of the current
instantaneous orbit estimate; apoapsis is its highest. These estimates treat
the craft and reference body as a two-body system at that instant. The actual
native flight can include other gravitating bodies and authored J2, so its
future can differ from a fixed ellipse.

| Direction | What the nose follows | Useful first experiment |
| --- | --- | --- |
| Prograde | Motion relative to the reference body | Near periapsis, a short burn mainly raises the opposite apoapsis |
| Retrograde | Opposite that motion | Near apoapsis, a short burn mainly lowers the opposite periapsis |
| Normal / anti-normal | Either side of the orbital plane | Compare how short burns tilt the plane at different orbital positions |
| Radial out / radial in | Away from / towards the host centre | Compare changes in shape and orientation; these are not simply circular-orbit altitude controls |

These are local manoeuvre rules, not promises for long burns or strongly
perturbed trajectories. Use the native preview to examine the actual proposal.
An unbound trajectory has no finite apoapsis. A negative periapsis means the
estimated orbit crosses the reference surface. Planetary surfaces and the
visible atmosphere do not currently supply terrain impacts or aerodynamic drag.

**Map** provides spatial context. Its coast prediction assumes the engine is
off and shows its source time. Recalculate after a burn; an old prediction is
not an automatic guidance programme.

## Control the view and the vehicle separately

**Orbit** looks around the spacecraft. **Chase** follows its attitude. **Map**
shows the larger orbit. **Encounter** on Android frames the two participating
craft when that mission provides one. **Centre** recovers the current view.
Dragging the scene changes the camera, not the nose.

**Rotate** commands pitch, yaw and roll. **RCS** commands translation along the
craft's own axes. **Fine** scales these controls to 20% authority independently
of main throttle. Opposite held directions cancel; releasing one restores the
remaining demand. Use short inputs, observe the motion, then counter it. Near
a station, relative speed and alignment matter more than orbital speed.

On desktop, W/A/S/D control pitch and yaw, Q/E control roll, and RCS reuses those
keys for translation. Plus/minus changes throttle, X cuts, C cycles views and R
centres. Space pauses/resumes when the scene has keyboard focus. Focus loss
clears controls and pauses. Android similarly clears held input on lifecycle
and mode handoffs.

Use the supplied encounter guidance for a first station approach and watch
range, closing speed and alignment. Docking needs compatible physical ports,
contact and appropriate arrival conditions. Engines are locked while attached.
**Undock** releases the joint; a separate RCS input moves away.

## Pick accuracy for the question

Leave leapfrog selected for ordinary flying. Fourth order is useful for smooth
conservative coast comparisons. Adaptive encounter integration is useful when
an encounter needs smaller internal steps. Neither raises the entire powered
flight to that order: thrust, attitude and contact use the positive-time
second-order split. Rejected calculations do not publish a partly advanced world.

The [scientific dynamics guide](scientific-dynamics.md) gives the controls,
independent references and limitations. The [authoring guide](spacecraft-authoring.md)
shows how to change mass, engines, propellant, hull and visuals, or create a
scenario, without application-specific spawn logic.

## Saves and your own spacecraft

Save a portable experiment to retain physical state and history. On desktop,
opening a replacement world downloads the previous flight first; adding a craft
retains the current world. Android materialises imported definitions as a saved
experiment before opening them.

Try the orbital workshop, then edit a copy of its JSON: rename a craft, change
engine thrust or propellant, and give its instance a fresh identifier. Import
the file and compare fuel flow and manoeuvres. The same definition works in
the native CLI and graphical hosts. New visual meshes use the offline catalogue
and require a rebuild; definitions can reuse compiled assets immediately.
