#include "../../main/cpp/SpacecraftPresentation.h"
#include "../../main/cpp/ReferenceSpacecraftMesh.h"
#include "../../main/cpp/SolarLabPresentation.h"
#include <cassert>
#include <iostream>
#include <limits>

int main() {
    using namespace solarlab::spacecraft;
    constexpr double pi = 3.14159265358979323846;
    const auto forward = Rotate({0, static_cast<float>(std::sin(pi/4)), 0, static_cast<float>(std::cos(pi/4))}, {0,0,1});
    assert(std::abs(forward[0]-1) < 1e-6 && std::abs(forward[2]) < 1e-6);
    for (const auto q : {std::array<float,4>{0,0,0,1}, std::array<float,4>{.2f,.3f,.4f,.8f}}) {
        const auto v=Rotate(q,{2,3,4});
        assert(std::abs(v[0]*v[0]+v[1]*v[1]+v[2]*v[2]-29) < 1e-5);
        const auto angles=ChaseAngles(q);
        const auto basis=solarlab::presentation::MakeCameraBasis(angles[0],angles[1]);
        const auto direction=Rotate(q,{0,-.3,1});
        const double dot=basis.forward[0]*direction[0]+basis.forward[1]*direction[1]+basis.forward[2]*direction[2];
        assert(dot > 1.04); // same body-frame forward direction, not mirrored
    }
    assert((NormalizedAttitude({0,0,0,0}) == std::array<float,4>{0,0,0,1}));
    assert((NormalizedAttitude({NAN,0,0,1}) == std::array<float,4>{0,0,0,1}));
    for (double reference : {7.1e6,149597870700.,-4.5e12}) {
        const auto origin=Split(reference), offset=Split(reference+.125);
        const float local=(offset.high-origin.high)+(offset.low-origin.low);
        assert(std::abs(local-.125f) < .02f);
    }
    assert(std::abs(SmoothAngle(pi-.01,-pi+.01,.5)-pi) < 1e-10);
    assert(kReferenceMesh.size() % 3 == 0 && kReferenceMesh.size() > 3000);
    assert(kReferenceExhaust.size() % 3 == 0 && !kReferenceExhaust.empty());
    double minZ=100,maxZ=-100,minX=100,maxX=-100;
    for(const auto& v:kReferenceMesh) {
        assert(std::isfinite(v.x+v.y+v.z+v.nx+v.ny+v.nz));
        assert(std::abs(v.nx*v.nx+v.ny*v.ny+v.nz*v.nz-1) < 1e-5);
        assert(v.effect==0);
        minZ=std::min(minZ,static_cast<double>(v.z));maxZ=std::max(maxZ,static_cast<double>(v.z));
        minX=std::min(minX,static_cast<double>(v.x));maxX=std::max(maxX,static_cast<double>(v.x));
    }
    assert(minZ < -4 && maxZ > 3.5 && maxZ-minZ < 8); // metres, nose +Z
    assert(maxX-minX > 13 && maxX-minX < 14);
    for(const auto& v:kReferenceExhaust) {assert(v.effect==1);assert(v.z <= -4.029f);}
    std::cout << "PASS spacecraft geometry, materials, attitude, chase frame, astronomical precision\n";
}
