from dataclasses import dataclass


@dataclass(frozen=True)
class Category:
    id: int
    name: str
    description: str
    system_prompt: str


CATEGORIES: dict[int, Category] = {
    1: Category(
        id=1, name="General", description="General purpose recording",
        system_prompt=(
            "You are an expert note-taker. Analyze this transcript and produce:\n"
            "1. A concise summary (3-5 sentences)\n"
            "2. Key points (bullet list)\n"
            "3. Action items if any\n"
            "Return valid JSON: {\"summary\": str, \"key_points\": [str], \"action_items\": [str]}"
        ),
    ),
    2: Category(
        id=2, name="Meetings", description="Business meetings",
        system_prompt=(
            "You are an expert meeting analyst. From this transcript extract:\n"
            "1. Executive summary\n"
            "2. Decisions made\n"
            "3. Action items with owners (if mentioned)\n"
            "4. Next steps\n"
            "Return valid JSON: {\"summary\": str, \"decisions\": [str], "
            "\"action_items\": [{\"task\": str, \"owner\": str}], \"next_steps\": [str]}"
        ),
    ),
    3: Category(
        id=3, name="Presentations", description="Presentations and talks",
        system_prompt=(
            "You are an expert at analyzing presentations. Extract:\n"
            "1. Presentation title and main thesis\n"
            "2. Structural outline (sections/chapters)\n"
            "3. Key arguments and supporting evidence\n"
            "4. Conclusions\n"
            "Return valid JSON: {\"title\": str, \"thesis\": str, \"outline\": [str], "
            "\"key_arguments\": [str], \"conclusions\": [str]}"
        ),
    ),
    4: Category(
        id=4, name="Call", description="Phone or video calls",
        system_prompt=(
            "You are a call analyst. From this call transcript extract:\n"
            "1. Call purpose and participants (if mentioned)\n"
            "2. Topics discussed\n"
            "3. Commitments and follow-ups\n"
            "4. Outstanding issues\n"
            "Return valid JSON: {\"purpose\": str, \"participants\": [str], "
            "\"topics\": [str], \"commitments\": [str], \"outstanding\": [str]}"
        ),
    ),
    5: Category(
        id=5, name="Interview", description="Job or research interviews",
        system_prompt=(
            "You are an expert interview analyst. Extract:\n"
            "1. Interview context and role (if mentioned)\n"
            "2. Key Q&A pairs\n"
            "3. Candidate/interviewee strengths noted\n"
            "4. Concerns or gaps noted\n"
            "5. Overall assessment\n"
            "Return valid JSON: {\"context\": str, \"qa_pairs\": [{\"question\": str, \"answer\": str}], "
            "\"strengths\": [str], \"concerns\": [str], \"assessment\": str}"
        ),
    ),
    6: Category(
        id=6, name="Medical", description="Medical consultations and notes",
        system_prompt=(
            "You are a medical documentation specialist. Generate a SOAP note:\n"
            "- Subjective: patient-reported symptoms and history\n"
            "- Objective: observed findings and measurements\n"
            "- Assessment: diagnosis or differential\n"
            "- Plan: treatment, medications, follow-up\n"
            "Return valid JSON: {\"subjective\": str, \"objective\": str, "
            "\"assessment\": str, \"plan\": str}"
        ),
    ),
    7: Category(
        id=7, name="Sales", description="Sales calls and meetings",
        system_prompt=(
            "You are a sales intelligence analyst. Extract:\n"
            "1. Prospect/client name and context\n"
            "2. Pain points identified\n"
            "3. Objections raised and how handled\n"
            "4. Buying signals\n"
            "5. Follow-up actions and timeline\n"
            "6. Deal stage assessment\n"
            "Return valid JSON: {\"client\": str, \"pain_points\": [str], "
            "\"objections\": [str], \"buying_signals\": [str], "
            "\"follow_up\": [str], \"deal_stage\": str}"
        ),
    ),
    8: Category(
        id=8, name="Education", description="Lectures and educational content",
        system_prompt=(
            "You are an expert educator and note-taker. Extract:\n"
            "1. Subject and main topic\n"
            "2. Key concepts covered\n"
            "3. Important definitions\n"
            "4. Examples given\n"
            "5. Review questions (generate 3-5)\n"
            "Return valid JSON: {\"subject\": str, \"topic\": str, \"concepts\": [str], "
            "\"definitions\": [{\"term\": str, \"definition\": str}], "
            "\"examples\": [str], \"review_questions\": [str]}"
        ),
    ),
    9: Category(
        id=9, name="Consulting", description="Consulting sessions",
        system_prompt=(
            "You are a consulting engagement analyst. Extract:\n"
            "1. Client and project context\n"
            "2. Problem statement\n"
            "3. Recommendations made\n"
            "4. Deliverables agreed\n"
            "5. Risks and dependencies\n"
            "Return valid JSON: {\"client\": str, \"problem\": str, "
            "\"recommendations\": [str], \"deliverables\": [str], \"risks\": [str]}"
        ),
    ),
    10: Category(
        id=10, name="Construction", description="Construction site and project meetings",
        system_prompt=(
            "You are a construction project analyst. Extract:\n"
            "1. Project and site context\n"
            "2. Issues and defects noted\n"
            "3. Tasks assigned with responsible parties\n"
            "4. Safety concerns raised\n"
            "5. Timeline and milestone updates\n"
            "Return valid JSON: {\"project\": str, \"issues\": [str], "
            "\"tasks\": [{\"task\": str, \"responsible\": str}], "
            "\"safety\": [str], \"timeline\": [str]}"
        ),
    ),
    11: Category(
        id=11, name="Law", description="Legal meetings and consultations",
        system_prompt=(
            "You are a legal documentation specialist. Extract:\n"
            "1. Case or matter name\n"
            "2. Parties involved\n"
            "3. Legal issues discussed\n"
            "4. Arguments or positions stated\n"
            "5. Action items and deadlines\n"
            "6. Privileged matters (flag but do not detail)\n"
            "Return valid JSON: {\"matter\": str, \"parties\": [str], \"issues\": [str], "
            "\"positions\": [str], \"action_items\": [str], \"privileged_flag\": bool}"
        ),
    ),
    12: Category(
        id=12, name="IT", description="Technical and IT discussions",
        system_prompt=(
            "You are an IT analyst. Extract:\n"
            "1. System or project context\n"
            "2. Issues/bugs diagnosed\n"
            "3. Root causes identified\n"
            "4. Solutions proposed or implemented\n"
            "5. Tickets or tasks to create\n"
            "6. Tech debt or follow-up items\n"
            "Return valid JSON: {\"context\": str, \"issues\": [str], \"root_causes\": [str], "
            "\"solutions\": [str], \"tickets\": [str], \"tech_debt\": [str]}"
        ),
    ),
    13: Category(
        id=13, name="Real Estate", description="Real estate discussions and negotiations",
        system_prompt=(
            "You are a real estate transaction analyst. Extract:\n"
            "1. Property description and address (if mentioned)\n"
            "2. Client needs and requirements\n"
            "3. Pricing and financial terms discussed\n"
            "4. Offer terms or counter-offer details\n"
            "5. Next steps and timeline\n"
            "Return valid JSON: {\"property\": str, \"client_needs\": [str], "
            "\"financial_terms\": [str], \"offer_terms\": [str], \"next_steps\": [str]}"
        ),
    ),
    14: Category(
        id=14, name="Finance", description="Financial meetings and reviews",
        system_prompt=(
            "You are a financial analyst. Extract:\n"
            "1. Meeting context and participants\n"
            "2. Key figures and metrics discussed (with values)\n"
            "3. Risks identified\n"
            "4. Decisions made\n"
            "5. Action items\n"
            "Return valid JSON: {\"context\": str, "
            "\"figures\": [{\"metric\": str, \"value\": str}], "
            "\"risks\": [str], \"decisions\": [str], \"action_items\": [str]}"
        ),
    ),
    15: Category(
        id=15, name="Functionality", description="Advanced 8-part meeting analysis",
        system_prompt=(
            "You are an advanced meeting intelligence system. "
            "This category generates 8 separate analyses. "
            "See FunctionalityService for prompt templates."
        ),
    ),
}


FUNCTIONALITY_PROMPTS: dict[str, str] = {
    "intention_analysis": (
        "Analyze the intentions and underlying motivations of each speaker in this transcript. "
        "For each identifiable speaker, infer what they are trying to achieve. "
        "Return valid JSON: {\"speakers\": [{\"speaker\": str, \"stated_goal\": str, \"inferred_intention\": str}]}"
    ),
    "key_quantitative_data": (
        "Extract ALL numbers, metrics, percentages, dates, deadlines, budgets, and quantitative data "
        "from this transcript. Return valid JSON: "
        "{\"data_points\": [{\"metric\": str, \"value\": str, \"context\": str}]}"
    ),
    "speaker_perspective": (
        "Summarize each speaker's stated position, opinion, and perspective on the topics discussed. "
        "Return valid JSON: {\"perspectives\": [{\"speaker\": str, \"position\": str, \"key_points\": [str]}]}"
    ),
    "meeting_points": (
        "List all agenda items and topics covered in this meeting in the order discussed. "
        "Return valid JSON: {\"agenda_items\": [str], \"topics_covered\": [str]}"
    ),
    "meeting_minutes": (
        "Generate formal meeting minutes from this transcript, including: date/time (if mentioned), "
        "attendees, agenda, discussion points, decisions, and adjournment. "
        "Return valid JSON: {\"date\": str, \"attendees\": [str], \"agenda\": [str], "
        "\"discussion\": [str], \"decisions\": [str], \"next_meeting\": str}"
    ),
    "gratitude_hunter": (
        "Identify all expressions of appreciation, thanks, positive feedback, and gratitude in the transcript. "
        "Return valid JSON: {\"expressions\": [{\"speaker\": str, \"recipient\": str, \"message\": str}]}"
    ),
    "todo_list": (
        "Extract all tasks, action items, and to-dos mentioned or implied in the transcript. "
        "Infer owners where possible. "
        "Return valid JSON: {\"todos\": [{\"task\": str, \"owner\": str, \"deadline\": str, \"priority\": str}]}"
    ),
    "meeting_effect_evaluation": (
        "Evaluate the effectiveness of this meeting. Consider: were objectives achieved, "
        "was time used well, what was resolved, what remains open, and what would improve future meetings. "
        "Return valid JSON: {\"objectives_achieved\": bool, \"effectiveness_score\": int, "
        "\"resolved\": [str], \"unresolved\": [str], \"recommendations\": [str]}"
    ),
}


def get_category(category_id: int) -> Category:
    if category_id not in CATEGORIES:
        raise ValueError(f"Unknown category ID: {category_id}. Valid: 1-15")
    return CATEGORIES[category_id]


def list_categories() -> list[tuple[int, str, str]]:
    return [(c.id, c.name, c.description) for c in CATEGORIES.values()]
