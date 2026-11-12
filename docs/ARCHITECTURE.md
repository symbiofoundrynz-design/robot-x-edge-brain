# Architecture overview

Operator text or final offline speech transcript
  -> MainActivity / ConversationCoordinator
     -> Deterministic action classification
        -> RobotController -> BLE transport -> robot
     -> Conversational/knowledge pathway
        -> local facts / retrieval -> ChatBrain -> LiteRT-LM
        -> UI response / offline speech output

CameraX -> visual observation/context -> conversational pathway

Safety boundary: LLM-generated text and retrieved knowledge are not
direct physical motor commands. STOP and timed-stop behavior are
implemented in the deterministic controller.

Experimental verified-response pipeline and lifecycle components exist
in source but remain separate from the live legacy replyGrounded path.
No current-source hardware certification is claimed.
