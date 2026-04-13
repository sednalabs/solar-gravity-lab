import com.graciousgazelles.solarlab.core.math.Vector3d
import com.graciousgazelles.solarlab.core.model.PhysicalConstants
import com.graciousgazelles.solarlab.render.core.*

val frame = RenderSceneFrame(
  epochSeconds = 0.0,
  authoritativeBodies = listOf(
    RenderBody("sun","Sun",Vector3d.ZERO, radiusM = 1.0, colorArgb = 0xFFFFFFFF.toInt(), kind = RenderBodyKind.STAR, isMassive = true)
  ),
  tracerBodies = (0 until 100).map {
    RenderBody("t$it","t$it",Vector3d(0.1*PhysicalConstants.ASTRONOMICAL_UNIT_M,it*1000.0,0.0), radiusM = 1.0, colorArgb = 0xFFFFFFFF.toInt(), kind = RenderBodyKind.ASTEROID, isMassive = false)
  },
  trails = listOf(RenderTrail("sun",0xFFFFFFFF.toInt(),0.25f,List(50){ Vector3d(it*1000.0,0.0,0.0)})),
  sourceRevision = 42L,
)
val packet = NativeScenePacket.fromScene(frame, CameraState(viewRadiusM = PhysicalConstants.ASTRONOMICAL_UNIT_M), 1920, 1080, ScenePacketBuildPolicy(nearTracerBudget=12,mediumTracerBudget=0,farTracerBudget=0,maxTrailVerticesPerTrail=8))
check(packet.sourceRevision == 42L)
check(packet.tracerNearCount == 12)
check(packet.trailVertexCounts.first() <= 8)
println("smoke-ok authoritative=${packet.authoritativeCount} near=${packet.tracerNearCount}")
