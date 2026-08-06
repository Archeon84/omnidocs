package com.omnidocs.app.ui.screens.editor

data class NoteTemplate(
    val id: String,
    val name: String,
    val icon: String,
    val title: String,
    val content: String
)

val noteTemplates = listOf(
    NoteTemplate(
        id = "meeting",
        name = "Meeting Notes",
        icon = "📅",
        title = "Meeting - ",
        content = """<h2>Meeting Notes</h2>
<p><strong>Date:</strong> </p>
<p><strong>Attendees:</strong> </p>
<hr/>
<h3>Agenda</h3>
<ul><li></li></ul>
<h3>Discussion</h3>
<p></p>
<h3>Action Items</h3>
<ul><li></li></ul>
<h3>Next Steps</h3>
<p></p>"""
    ),
    NoteTemplate(
        id = "research",
        name = "Research Notes",
        icon = "🔬",
        title = "Research: ",
        content = """<h2>Research Notes</h2>
<p><strong>Topic:</strong> </p>
<p><strong>Source:</strong> </p>
<hr/>
<h3>Key Findings</h3>
<ul><li></li></ul>
<h3>Analysis</h3>
<p></p>
<h3>References</h3>
<ul><li></li></ul>"""
    ),
    NoteTemplate(
        id = "journal",
        name = "Daily Journal",
        icon = "📝",
        title = "Daily Journal - ",
        content = """<h2>Daily Journal</h2>
<h3>Mood</h3>
<p></p>
<h3>Highlights</h3>
<ul><li></li></ul>
<h3>Challenges</h3>
<ul><li></li></ul>
<h3>Gratitude</h3>
<ul><li></li></ul>
<h3>Tomorrow's Intentions</h3>
<ul><li></li></ul>"""
    ),
    NoteTemplate(
        id = "project",
        name = "Project Plan",
        icon = "🎯",
        title = "Project: ",
        content = """<h2>Project Plan</h2>
<p><strong>Goal:</strong> </p>
<p><strong>Deadline:</strong> </p>
<hr/>
<h3>Tasks</h3>
<ul><li>[ ] </li></ul>
<h3>Resources Needed</h3>
<ul><li></li></ul>
<h3>Risks</h3>
<ul><li></li></ul>
<h3>Progress</h3>
<p></p>"""
    ),
    NoteTemplate(
        id = "blank",
        name = "Blank Note",
        icon = "📄",
        title = "",
        content = ""
    )
)