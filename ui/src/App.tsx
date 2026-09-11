import { HttpAgent } from "@ag-ui/client";
import { CopilotChat, CopilotKit } from "@copilotkit/react-core/v2";

// The browser talks to the harness directly -- there is no runtime in between, which
// is why the server carries CORS. One agent, registered as "default" so CopilotChat
// picks it up with no agentId.
const agent = new HttpAgent({ url: "http://localhost:8080/" });

export default function App() {
  return (
    <CopilotKit agents__unsafe_dev_only={{ default: agent }}>
      <div style={{ height: "100vh" }}>
        <CopilotChat />
      </div>
    </CopilotKit>
  );
}
