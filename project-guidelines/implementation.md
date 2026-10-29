# Implementation Guideline for changes and builds in this Project

## Log your actions
* All your actions should be logged under a folder(named after the date["YYYY-MM-DD"] the action was done. If one already exists for the current session with the appropiate name and date, use that one, otherwise create another one) under "/project-changelog". The markdown file should contain the details of said change/build(ESPECIALLY the time it was done), along with the impacts that come with it.
    - This rule DOES NOT apply to any untracked components(anything applicable to ".gitignore")
    - Files should contain a "Performed By"(If AI-Agent, state which model/brand, if user, write down your name) and "Approved By"(Who approved the action? If User, write down your name. If AI-Agent, state the Agent's model/brand)

## Never edit off the bat
* You can read through the project, but any actions intended to make permamnent or temporary changes should be designed in planning mode to avoid conflict with project structure. NEVER automatically edit anything in the repo without explicit permission given.

## Always ask questions
* When uncertain about any elements of the project, or any elements regarding the change or build, the user/AI-Agent(s) responsible should consult the project manager with any questions they may have.

## Version control
* When creating builds, ALWAYS store the prior version of whatever component is changed, updated, or modified in "/version-control." This helps us rewind whenever the repo breaks, and also helps mix-match components to optimize productivity. This rule applies to but is not limited to:
    - Packages/Dependencies
    - Modules/Scripts
    - Application Builds
    - Servers
    * This rule however does exclude documentation(we are mainly focused on keeping version control for application components) such as:
        - Guidelines
        - Changelogs 
        - Notes
    * Rule will NEVER apply to any critical components that can compromise the following, but not limited to:
        - API Keys
        - Webhooks
        - Secrets
        - Hard-Coded Secrets(SHOULDNT BE PRESENT AT ALL)
        - Plain-text Secrets(SHOULDNT BE PRESENT AT ALL)

## Environment variables
* When introducing new variables, ALWAYS update the "/.env_example" to reflect what the ".env" would look like(obviously keep the variables empty, do NOT give them any values, they are there to serve as a reference and guide to setup the application), along with proper annotation on what the key/variable/secret does, and how to set it up.
    - Do not be an idiot and expose the secret/api value through the fucking annotation

## Floor Planning
* Whenever the user or AI-Agent is modifying/constructing any UI Elements, it is best and preferred practice to present the project manager with clear mockups instead of "winging it"(acting without input).
    - Ask the project manager in what form/manner would they like the mockups presented to them

## Budgeting
* Whenever choosing from dependencies or third party services. AI-Agent(s) and users should always remain price conscious. Unless explicitly stated otherwise, the resources you use SHOULD NEVER require upfront card details nor payment. Prioritize a secure, cost-efficient resource for the application's stack composition.
    - ALWAYS check the resource's website/documentation, search the pages in their ENTIRETY. Somewhere will usually indicate if the service is paid-only or free to use in it's entirety. Do not just take first glances and assume a verdict.
* Regarding token usage, Users and AI-Agent(s) should make efforts to keep token usage low to respect any budget/session limits said User/Agent may have. However, this should not come at a cost of the rules set in place for this project. The Security and Implementation still remain core parts of a smooth, concised, and professional workflow.

## Open Source and Privacy
* Whenever the User or AI-Agent(s) choose their resources, dependencies and or third party services. ALWAYS look into the telemetry behind it. Look for an application that respects the privacy of the consumer(s), whilst still being structurally sound and firm in it's Security.
* When implementing third party packages or any resource, always examine the code. Never  take it at face value. Look for any traces of telemetry or tracking  related functions/programs. 