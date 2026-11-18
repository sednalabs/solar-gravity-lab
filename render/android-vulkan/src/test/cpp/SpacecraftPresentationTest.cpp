#include "../../main/cpp/SpacecraftPresentation.h"
#include "../../main/cpp/ReferenceSpacecraftMesh.h"
#include "../../main/cpp/RelayStationMesh.h"
#include "../../main/cpp/SpacecraftMeshBatches.h"
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
    // A 60-degree short-axis perspective must resolve the actual Earth horizon
    // from a 400 km orbit and still retain metre-scale local mesh depth.
    constexpr double earthRadius=6371000.0;
    const double eyeDistance=FlightEyeDistance(16.0);
    assert(std::abs(eyeDistance-27.71281292110204)<1e-10);
    const auto centerRay=FlightCameraRay(360,360,720,720);
    assert((centerRay==std::array<double,3>{0,0,1}));
    const auto edgeRay=FlightCameraRay(0,360,720,720);
    assert(std::abs(edgeRay[0]+0.5)<1e-12 && std::abs(edgeRay[2]-std::sqrt(0.75))<1e-12);
    const std::array<double,3> earthCenter{-earthRadius-400000.0,0,eyeDistance};
    assert(!RaySphere(centerRay,earthCenter,earthRadius));
    const auto horizon=RaySphere(edgeRay,earthCenter,earthRadius);
    assert(horizon && *horizon>400000.0 && *horizon<3000000.0);
    const auto nearShip=RaySphere(centerRay,{0,0,eyeDistance},4.0);
    assert(nearShip && std::abs(*nearShip-(eyeDistance-4.0))<1e-10);
    assert(!RaySphere(centerRay,{0,0,-100.0},10.0));
    assert(FlightDepth(*nearShip)<FlightDepth(*horizon*edgeRay[2]));
    assert(FlightDepth(400000.0)<FlightDepth(384400000.0));
    assert(FlightDepth(384400000.0)<FlightDepth(149597870700.0));
    assert(FlightDepth(0.1)>0.0 && FlightDepth(1.0e16)==1.0);
    // Pixel aspect preserves the same short-axis angle for a landscape stage.
    assert(FlightCameraRay(1280/2.,720/2.,1280,720)==centerRay);
    assert(std::abs(FlightCameraRay(1280/2.,0,1280,720)[1]-0.5)<1e-12);
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
    assert(kRelayStationMesh.size() > kReferenceMesh.size() && kRelayStationMesh.size() % 3 == 0);
    assert(kRelayStationExhaust.empty());
    for(const auto& v:kRelayStationMesh) {
        assert(v.x*v.x+v.y*v.y+v.z*v.z <= 12.0f*12.0f);
        assert(std::abs(v.nx*v.nx+v.ny*v.ny+v.nz*v.nz-1) < 1e-5);
        assert(v.effect==0);
    }
    // Mixed scene order must not attach a station mesh or plume to the tug.
    const std::array<uint32_t,6> kinds{2,1,99,2,1,0};
    const auto plan=PlanMeshInstances(kinds);
    assert((plan.sourceIndices==std::vector<uint32_t>{1,4,0,3}));
    assert(plan.ranges[0].firstInstance==0 && plan.ranges[0].instanceCount==2);
    assert(plan.ranges[1].firstInstance==2 && plan.ranges[1].instanceCount==2);
    const auto stationOnly=PlanMeshInstances(std::array<uint32_t,1>{2});
    assert(stationOnly.ranges[0].instanceCount==0);
    assert(stationOnly.ranges[1].firstInstance==0 && stationOnly.ranges[1].instanceCount==1);
    assert(PlanMeshInstances(std::array<uint32_t,0>{}).sourceIndices.empty());
    // Both physical envelopes remain inside the perspective frustum through the
    // complete approach, at AU-scale origins and between submitted pose samples.
    for(double separation:{1000.0,250.0,75.0}) {
        const std::array<double,3> ship{149597870700.,-separation,100.};
        const std::array<double,3> target{149597870700.,0.,100.};
        const std::array<double,3> previousShip{ship[0]-.125,ship[1]-2,ship[2]};
        const std::array<double,3> previousTarget{target[0]-.125,target[1]-1,target[2]};
        const auto frame=EncounterFrame(ship,target,previousShip,previousTarget,8,12);
        assert(frame);
        // At one AU, a double's spacing is about 0.031 mm. Bound the absolute
        // centre error below 0.1 mm; retain a tighter bound on the local axis.
        assert(std::abs(frame->center[0]-ship[0]) < 1e-4);
        assert(std::abs(frame->center[1]+separation*.5) < 1e-12);
        assert(frame->previousOffset[0]==-.125 && frame->previousOffset[1]==-1.5);
        assert(std::abs(frame->yawRadians-pi*.5)<1e-12);
        const auto basis=solarlab::presentation::MakeCameraBasis(frame->yawRadians,frame->pitchRadians);
        const double eye=FlightEyeDistance(frame->viewRadiusM);
        for(double fraction:{0.,.25,.5,.75,1.}) {
            for(size_t body=0;body<2;++body) {
                const auto& current=body?target:ship;
                const auto& previous=body?previousTarget:previousShip;
                const double radius=body?12.:8.;
                std::array<double,3> relative{};
                for(size_t axis=0;axis<3;++axis) relative[axis]=
                    (previous[axis]-frame->center[axis]-frame->previousOffset[axis])*(1-fraction)+
                    (current[axis]-frame->center[axis])*fraction;
                const double cameraZ=eye+relative[0]*basis.forward[0]+relative[1]*basis.forward[1]+relative[2]*basis.forward[2];
                const double cameraX=relative[0]*basis.right[0]+relative[1]*basis.right[1]+relative[2]*basis.right[2];
                const double cameraY=relative[0]*basis.up[0]+relative[1]*basis.up[1]+relative[2]*basis.up[2];
                assert(cameraZ>radius+.1);
                assert((std::abs(cameraX)+radius)/(cameraZ-radius)<std::tan(pi/6));
                assert((std::abs(cameraY)+radius)/(cameraZ-radius)<std::tan(pi/6));
            }
        }
    }
    assert(!EncounterFrame({0,0,0},{NAN,0,0},{0,0,0},{0,0,0},8,12));
    assert(!EncounterFrame({-1e308,0,0},{1e308,0,0},{-1e308,0,0},{1e308,0,0},8,12));
    assert(!EncounterFrame({0,0,0},{0,75,0},{0,0,0},{0,75,0},8,1.7e308));
    std::cout << "PASS spacecraft/station meshes, draw ranges, attitude, astronomical precision, perspective horizon, encounter framing and shared depth\n";
}
