# Earth and Moon surface colour maps

These are the original, unmodified 1024 × 512 image files from NASA's
Scientific Visualization Studio. They are packaged for offline use and decoded
once per Vulkan device using Android's native image decoder. The two RGBA maps
occupy 4 MiB of GPU buffer storage; bilinear lookup wraps longitude and clamps
latitude. Camera navigation does not rotate the map's body-fixed meridian.

| File | Source and credit | SHA-256 |
| --- | --- | --- |
| `earth-nasa.png` | [Blue Marble, SVS 2915](https://svs.gsfc.nasa.gov/2915/). NASA/Goddard Space Flight Center Scientific Visualization Studio; Blue Marble data courtesy of Reto Stöckli (NASA/GSFC) and NASA's Earth Observatory. | `d1d3cb36ae6ae452c43cdf7ae0ffc13a959e8a5a2358f58b05e5f623c15119f9` |
| `moon-nasa.jpg` | [CGI Moon Kit, SVS 4720](https://svs.gsfc.nasa.gov/4720/), 2019 colour map. NASA's Scientific Visualization Studio; data assembled by the Lunar Reconnaissance Orbiter Camera and Lunar Orbiter Laser Altimeter teams. | `b246064f217f8d479df78c49c7c8595a8f5fbda008a72fd539978d2e121e0109` |

Original downloads, retrieved 16 November 2026 UTC:

- <https://svs.gsfc.nasa.gov/vis/a000000/a002900/a002915/bluemarble-1024.png>
- <https://svs.gsfc.nasa.gov/vis/a000000/a004700/a004720/lroc_color_poles_1k.jpg>

The maps illustrate the surfaces. They do not represent live weather, a terrain
mesh, lunar libration, or a qualified surface-orientation ephemeris. The existing
surface orientation remains illustrative. Positions, velocities, masses and
physical radii still come from the authoritative simulation. Other bodies keep
their procedural materials. The Moon Kit itself describes its colour maps as
optimised for visual presentation rather than scientific measurement.

Use and attribution follow the [NASA media guidelines](https://www.nasa.gov/nasa-brand-center/images-and-media/).
NASA has not reviewed or endorsed Solar Gravity Lab.
