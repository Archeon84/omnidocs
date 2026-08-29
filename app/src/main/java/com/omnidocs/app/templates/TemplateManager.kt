package com.omnidocs.app.templates

/**
 * Manages available note templates.
 */
object TemplateManager {

    val allTemplates: List<NoteTemplate> = listOf(
        MeetingTemplate(),
        LectureTemplate(),
        InterviewTemplate(),
        ResearchTemplate(),
        JournalTemplate(),
        ProjectUpdateTemplate(),
        DecisionLogTemplate()
    )

    fun getTemplate(id: String): NoteTemplate? = allTemplates.find { it.id == id }

    fun getTemplateNames(): List<Pair<String, String>> = allTemplates.map { it.id to it.name }
}

// --- Template Implementations ---

class MeetingTemplate : NoteTemplate {
    override val id = "meeting"
    override val name = "Meeting"
    override val description = "Meeting notes with decisions, action items, and attendees"
    override val icon = "📅"

    override val htmlContent = """
        <h2>Meeting Notes</h2>
        <p><strong>Date:</strong> </p>
        <p><strong>Attendees:</strong> </p>
        <hr/>
        <h2>Agenda</h2>
        <ul><li></li></ul>
        <h2>Discussion</h2>
        <p></p>
        <h2>Decisions</h2>
        <ul><li></li></ul>
        <h2>Action Items</h2>
        <ul><li><input type="checkbox" disabled> </li></ul>
        <h2>Follow-up</h2>
        <p></p>
    """.trimIndent()

    override val expectedFields = listOf("Date", "Attendees", "Agenda", "Discussion", "Decisions", "Action Items", "Follow-up")
    override val extractionTypes = listOf("decision", "task", "fact", "question")
    override val suggestedPrompts = listOf(
        "Extract all decisions made in this meeting",
        "List all action items with owners and deadlines",
        "Identify any open questions that need follow-up"
    )
}

class LectureTemplate : NoteTemplate {
    override val id = "lecture"
    override val name = "Lecture"
    override val description = "Lecture notes with key concepts, definitions, and questions"
    override val icon = "🎓"

    override val htmlContent = """
        <h2>Lecture Notes</h2>
        <p><strong>Course:</strong> </p>
        <p><strong>Topic:</strong> </p>
        <p><strong>Date:</strong> </p>
        <hr/>
        <h2>Key Concepts</h2>
        <ul><li></li></ul>
        <h2>Definitions</h2>
        <p><strong>Term:</strong> Definition</p>
        <h2>Notes</h2>
        <p></p>
        <h2>Questions</h2>
        <ul><li></li></ul>
        <h2>Summary</h2>
        <p></p>
    """.trimIndent()

    override val expectedFields = listOf("Course", "Topic", "Date", "Key Concepts", "Definitions", "Notes", "Questions", "Summary")
    override val extractionTypes = listOf("fact", "question", "interpretation")
    override val suggestedPrompts = listOf(
        "Extract key concepts and their definitions",
        "List questions that need to be answered",
        "Summarize the main points of this lecture"
    )
}

class InterviewTemplate : NoteTemplate {
    override val id = "interview"
    override val name = "Interview"
    override val description = "Interview notes with key quotes, statements, and follow-ups"
    override val icon = "🗣️"

    override val htmlContent = """
        <h2>Interview Notes</h2>
        <p><strong>Interviewee:</strong> </p>
        <p><strong>Date:</strong> </p>
        <p><strong>Purpose:</strong> </p>
        <hr/>
        <h2>Key Quotes</h2>
        <blockquote></blockquote>
        <h2>Key Points</h2>
        <ul><li></li></ul>
        <h2>Observations</h2>
        <p></p>
        <h2>Follow-up Questions</h2>
        <ul><li></li></ul>
    """.trimIndent()

    override val expectedFields = listOf("Interviewee", "Date", "Purpose", "Key Quotes", "Key Points", "Observations", "Follow-up Questions")
    override val extractionTypes = listOf("fact", "interpretation", "question")
    override val suggestedPrompts = listOf(
        "Extract key quotes from the interviewee",
        "Identify main themes and observations",
        "List follow-up questions for next interview"
    )
}

class ResearchTemplate : NoteTemplate {
    override val id = "research"
    override val name = "Research"
    override val description = "Research session with findings, sources, and next steps"
    override val icon = "🔬"

    override val htmlContent = """
        <h2>Research Notes</h2>
        <p><strong>Topic:</strong> </p>
        <p><strong>Date:</strong> </p>
        <hr/>
        <h2>Research Question</h2>
        <p></p>
        <h2>Findings</h2>
        <ul><li></li></ul>
        <h2>Sources</h2>
        <ul><li></li></ul>
        <h2>Analysis</h2>
        <p></p>
        <h2>Next Steps</h2>
        <ul><li><input type="checkbox" disabled> </li></ul>
    """.trimIndent()

    override val expectedFields = listOf("Topic", "Date", "Research Question", "Findings", "Sources", "Analysis", "Next Steps")
    override val extractionTypes = listOf("fact", "task", "question")
    override val suggestedPrompts = listOf(
        "Summarize the key research findings",
        "List all sources referenced",
        "Identify gaps in the research"
    )
}

class JournalTemplate : NoteTemplate {
    override val id = "journal"
    override val name = "Voice Journal"
    override val description = "Personal journal entry with reflections and gratitude"
    override val icon = "📖"

    override val htmlContent = """
        <h2>Journal Entry</h2>
        <p><strong>Date:</strong> </p>
        <p><strong>Mood:</strong> </p>
        <hr/>
        <h2>What happened today</h2>
        <p></p>
        <h2>Reflections</h2>
        <p></p>
        <h2>Gratitude</h2>
        <ul><li></li></ul>
        <h2>Tomorrow's focus</h2>
        <p></p>
    """.trimIndent()

    override val expectedFields = listOf("Date", "Mood", "What happened today", "Reflections", "Gratitude", "Tomorrow's focus")
    override val extractionTypes = listOf("task", "interpretation")
    override val suggestedPrompts = listOf(
        "Extract any action items or goals mentioned",
        "Identify themes and patterns in this journal entry"
    )
}

class ProjectUpdateTemplate : NoteTemplate {
    override val id = "project_update"
    override val name = "Project Update"
    override val description = "Project status update with progress, blockers, and next milestones"
    override val icon = "📋"

    override val htmlContent = """
        <h2>Project Update</h2>
        <p><strong>Project:</strong> </p>
        <p><strong>Date:</strong> </p>
        <p><strong>Status:</strong> <span style="color: green;">On Track</span></p>
        <hr/>
        <h2>Progress</h2>
        <ul><li></li></ul>
        <h2>Blockers</h2>
        <ul><li></li></ul>
        <h2>Next Milestones</h2>
        <ul><li></li></ul>
        <h2>Notes</h2>
        <p></p>
    """.trimIndent()

    override val expectedFields = listOf("Project", "Date", "Status", "Progress", "Blockers", "Next Milestones", "Notes")
    override val extractionTypes = listOf("task", "deadline", "fact")
    override val suggestedPrompts = listOf(
        "Extract deadlines and milestones",
        "Identify blockers and risks",
        "List completed items"
    )
}

class DecisionLogTemplate : NoteTemplate {
    override val id = "decision_log"
    override val name = "Decision Log"
    override val description = "Record a decision with context, alternatives, and rationale"
    override val icon = "⚖️"

    override val htmlContent = """
        <h2>Decision Log</h2>
        <p><strong>Date:</strong> </p>
        <p><strong>Decision Maker:</strong> </p>
        <hr/>
        <h2>Decision</h2>
        <p><strong></strong></p>
        <h2>Context</h2>
        <p></p>
        <h2>Alternatives Considered</h2>
        <ul><li></li></ul>
        <h2>Rationale</h2>
        <p></p>
        <h2>Impact</h2>
        <p></p>
        <h2>Review Date</h2>
        <p></p>
    """.trimIndent()

    override val expectedFields = listOf("Date", "Decision Maker", "Decision", "Context", "Alternatives Considered", "Rationale", "Impact", "Review Date")
    override val extractionTypes = listOf("decision", "fact", "interpretation")
    override val suggestedPrompts = listOf(
        "Extract the core decision and its rationale",
        "Identify what alternatives were considered",
        "Note any conditions for revisiting this decision"
    )
}
