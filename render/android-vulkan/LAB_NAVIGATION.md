# Laboratory navigation and overlays

`SolarSystemRenderHostView` exposes the submitted native frame through
`setOnLabViewportChangedListener`. The immutable `LabViewportState` contains the
displayed camera, metres per physical pixel, and at most 128 body anchors (Earth,
Moon and the selected body have priority). It reads the native cached scene after
a successful presentation, using the same interpolation fraction as picking and
the GPU. It does not export a second physics snapshot. Identical payloads are not
republished. The app should place its overlay in the same bounds as the render
host; these coordinates do not include the window or a separate bottom dock.
The listener receives null on pause, surface creation/resize/destruction, session
replacement and release. A viewport epoch rejects queued results from before
those events, including resize results within the same native surface generation.
Callers must clear their copied state on null and wait for a fresh submitted frame
before accepting spatial input.

`LabViewportState.project` projects a simulation-frame position. `unprojectToPlane`
intersects an orthographic ray with an explicit `LabPlane(originM, normal)` and
returns null for invalid or nearly parallel geometry. The overlay and the native
camera share the right/up/forward basis and the half-span of the shorter viewport
dimension. Body anchor `occluded` means another visible sphere covers the body's
centre; it does not mean the entire body is hidden. Markers and label touch areas
are interface affordances, not physical radii.

`setLabMapPlane` declares the fixed authoring plane; `setLabMapMode(true)` orients
the native view down its normal. The plane's origin is used for placement and
does not recenter the camera. Returning to 3D restores the earlier 3D orientation
at the current centre and scale. The shell must name the plane accurately. The
renderer neither derives an orbital plane nor mutates simulation coordinates.

Map mode uses one finger to pan; 3D uses one finger to orbit about the view centre.
Two fingers pan and zoom in both, with zoom anchored to their actual midpoint.
The lab uses the direct pinch/button scale factor. Follow transports a subject
and the user's local pan/zoom offset together; it does not change pinch anchors.
Changing selection releases follow without reframing. Explicit `frameBody`
frames once; `focusAndFrameBody` also enables the requested follow mode. The lab's
free view remains Earth-translating with non-rotating axes. No navigation changes
authoritative positions or velocities.

During `PLACE_BODY`, one finger reports placement lifecycle updates in the
declared plane. A second finger cancels that draft gesture and takes over camera
navigation until all fingers are lifted. The caller should restore its pre-drag
draft on Cancelled. Native placement never falls back to a stale shell camera or
an invented centre hit. Reapplying an unchanged interaction mode during Compose
updates leaves an active gesture intact.

`currentLabViewportState()` returns the latest submitted state, and
`restoreLabCamera(camera)` restores a captured view in free navigation. The shell
owns view-mode, selection and follow restoration around its authoring workflow.

Native helper tests and JVM geometry tests share known projection fixtures and
cover true overhead/inclined planes, portrait/landscape, barycentric-coordinate
round trips, invalid rays, and density-aware touch padding. Native Android
journeys remain the acceptance evidence for real gestures, lifecycle and visuals.
