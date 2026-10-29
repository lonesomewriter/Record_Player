# Security Guidelines for this Project and Repo overall

## Double check before a commit
* Ensure there are no slips in security regarding keys, private mentions, or any critical infrastructure when adding a commit. Committing anything meant to be hidden is DANGEROUS and cannot be overriden. Risks the entire security of the project.

## Do not expose any critical infrastructure
* Always remember this repo IS PUBLIC. Treat it as such, and do not get lax with security. Always safeguard your critical information.
* AI Agents and users should stray from any commands, actions, text and or notes that can expose ANY secrets, keys, api, PII(Personal Identifiable Information), etc, to the console, terminal, agent-chat, or public view in general.
* When connecting webhooks, API Keys, etc. Always give only the priviledges that are ABSOLUTELY necessary for functionality to reduce attack surface.

## Always verify the authenticity and fortification of your code
* AI Agents and users should ALWAYS verify the security structure and effeciency of the code they put into this repo and project. This includes but is not limited to:
    - Packages and Dependencies, AI Agents and users should always check for any security vulnerabilities or possible areas of exploitation
    - Webhooks and API Keys, only the necessary permissions should ever be granted through these to minimize attack surfaces
    - Always research to see what ongoing issues may plaque the Package/Resource/Dependency
* Code written, borrowed, or reused, should always be up to OWASP and OSINT security standards

## Always update the ".gitignore" to house the critical components of this project
* AI Agents and users should ALWAYS keep the ".gitignore" up-to-date during the course of their time working in this repo. Any backend systems or database sets(especially keys or ".envs") should IMMEDIATELY be added to the ".gitignore" to avoid leaking any high-risk components to the public
* When making builds and applications, keep only one ".gitignore"(the one under the root directory) and CONSISTENTLY update the "/.gitignore" under the root directory. Always include critical infrastructure to builds, packages, and other applicable pieces within the ".gitignore" to prevent any critical leakage.

## Always clean up loose ends
* AI Agents and users should never leave ports or any other connection forms open. When ports/other connections have exceeded their use, and the AI-Agent(s) or user(s) are no longer use them, these connections should be CLOSED to minimize the attack surface and risk of bad actors injecting dangerous malware/code.