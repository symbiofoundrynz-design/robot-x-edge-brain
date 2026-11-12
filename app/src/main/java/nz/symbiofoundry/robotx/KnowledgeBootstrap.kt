package nz.symbiofoundry.robotx

object KnowledgeBootstrap {
    const val DATASET_VERSION = "0.1"

    fun records(): List<KnowledgeRecord> = listOf(
        KnowledgeRecord(
            id = "identity.robot_x",
            category = KnowledgeCategory.IDENTITY,
            title = "Robot identity",
            body = "Robot X is a small Android edge-AI robot platform built around an Apitor Robot X.",
            authority = KnowledgeAuthority.AUTHORITATIVE,
            source = "E2.6 bootstrap",
            tags = setOf("identity", "robot-x", "name")
        ),
        KnowledgeRecord(
            id = "hardware.phone",
            category = KnowledgeCategory.HARDWARE,
            title = "Robot X phone",
            body = "Robot X uses a Samsung Galaxy A03s, model SM-A037F, running Android 13.",
            authority = KnowledgeAuthority.AUTHORITATIVE,
            source = "E2.6 bootstrap",
            tags = setOf("hardware", "phone", "android")
        ),
        KnowledgeRecord(
            id = "software.app_package",
            category = KnowledgeCategory.SOFTWARE,
            title = "Android application package",
            body = "The Robot X Android application package is nz.symbiofoundry.robotx.",
            authority = KnowledgeAuthority.AUTHORITATIVE,
            source = "E2.6 bootstrap",
            tags = setOf("software", "android", "package")
        ),
        KnowledgeRecord(
            id = "capability.offline_voice_input",
            category = KnowledgeCategory.CAPABILITY,
            title = "Offline voice input",
            body = "Robot X supports offline voice input using sherpa-onnx with a Moonshine Tiny English model.",
            authority = KnowledgeAuthority.AUTHORITATIVE,
            source = "E2.6 bootstrap",
            tags = setOf("capability", "voice", "offline", "stt")
        ),
        KnowledgeRecord(
            id = "capability.offline_voice_output",
            category = KnowledgeCategory.CAPABILITY,
            title = "Offline voice output",
            body = "Robot X supports offline voice output using sherpa-onnx with the Piper en_US-lessac-medium voice.",
            authority = KnowledgeAuthority.AUTHORITATIVE,
            source = "E2.6 bootstrap",
            tags = setOf("capability", "voice", "offline", "tts")
        ),
        KnowledgeRecord(
            id = "limitation.visual_interpretation",
            category = KnowledgeCategory.CAPABILITY,
            title = "Local visual perception",
            body = "Robot X can use its camera and local perception model to identify supported objects in captured images and provide validated detections as grounded visual facts. Robot X must not infer identity, colour, text, material, or other properties not reported by the validated observation.",
            authority = KnowledgeAuthority.AUTHORITATIVE,
            source = "E3.4 B4C grounded vision acceptance",
            tags = setOf("capability", "camera", "vision", "perception", "grounding")
        ),
        KnowledgeRecord(
            id = "safety.motor_authority",
            category = KnowledgeCategory.SAFETY,
            title = "Motor authority",
            body = "RobotController is the sole final authority for Robot X physical actions.",
            authority = KnowledgeAuthority.AUTHORITATIVE,
            source = "E2.6 bootstrap",
            tags = setOf("safety", "motor", "controller")
        ),
        KnowledgeRecord(
            id = "safety.ai_boundary",
            category = KnowledgeCategory.SAFETY,
            title = "AI authority boundary",
            body = "The AI may suggest, interpret, and communicate, but it does not own physical action authority.",
            authority = KnowledgeAuthority.AUTHORITATIVE,
            source = "E2.6 bootstrap",
            tags = setOf("safety", "ai", "authority")
        ),
        KnowledgeRecord(
            id = "capability.offline_operation",
            category = KnowledgeCategory.CAPABILITY,
            title = "Offline operation",
            body = "Robot X voice input, voice output, and local knowledge are designed to operate without Wi-Fi or internet access.",
            authority = KnowledgeAuthority.AUTHORITATIVE,
            source = "E2.6 bootstrap",
            tags = setOf("capability", "offline", "network")
        ),
        KnowledgeRecord(
            id = "procedure.canonical_actions",
            category = KnowledgeCategory.PROCEDURE,
            title = "Canonical physical actions",
            body = "The canonical physical actions are FORWARD, REVERSE, TURN_LEFT, TURN_RIGHT, and STOP.",
            authority = KnowledgeAuthority.AUTHORITATIVE,
            source = "E2.6 bootstrap",
            tags = setOf("procedure", "actions", "movement")
        )
    )
}
