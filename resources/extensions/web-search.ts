import { defineTool, type ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "@earendil-works/pi-ai";
import { join } from "node:path";

export default function (pi: ExtensionAPI) {
	const interviewWebSearchTool = defineTool({
		name: "interview_web_search",
		label: "Interview web search",
		description: "Search the web for current facts such as framework details, versions, and benchmarks for the interview.",
		parameters: Type.Object({
			query: Type.String({ description: "Search query" }),
		}),

		async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
			const script = join(ctx.cwd, "tools", "web_search");
			const { stdout, stderr, code, killed } = await pi.exec(script, [params.query], {
				timeout: 25000,
			});

			if (code === 0 && !killed) {
				return {
					content: [{ type: "text", text: stdout }],
					details: undefined,
				};
			}

			const text = killed
				? `interview_web_search was killed (timeout or signal)`
				: `interview_web_search exited with code ${code}${stderr ? `: ${stderr}` : ""}`;
			return {
				content: [{ type: "text", text }],
				details: { code, killed, stderr },
				isError: true,
			};
		},
	});

	pi.registerTool(interviewWebSearchTool);
}
