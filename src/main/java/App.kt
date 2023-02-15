import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.scene.Scene
import javafx.scene.web.WebView
import javax.swing.*
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.io.InputStreamReader
import javax.swing.UIManager
import java.awt.*
import javax.swing.event.ListSelectionEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.JTree
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener
import javax.swing.plaf.metal.DefaultMetalTheme
import javax.swing.plaf.metal.MetalLookAndFeel
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import kotlin.collections.ArrayList
import javax.swing.text.html.HTMLDocument
import javax.swing.text.html.HTMLEditorKit


/**
 * Created by weston on 8/26/17.
 */

/**
 * TODO:
 *  +Webview toggling
 *  User profiles
 *  (periodic?) Refresh
 */

class App : PubSub.Subscriber {
    companion object {
        init {
//                MetalLookAndFeel.setCurrentTheme(DefaultMetalTheme())
//                UIManager.setLookAndFeel(MetalLookAndFeel())
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel")
        }
    }

    val progressBar = JProgressBar(0, 30)
    var contentPane = JPanel()
    var storyControlPanel = JPanel()
    var storyPanel: JPanel = JPanel()
    var mainRightPane = JPanel()

    val listModel = DefaultListModel<Story>()
    val storyList = JList(listModel)

    val userLabel = JLabel()
    val pointsLabel = JLabel()
    val storyTimeLabel = JLabel()
    val storyURLLabel = JLabel()
    val commentCountLabel = JLabel()
    val contentToggleButton = JButton("View Page")
    var headlinePanel = JPanel()
    var storyIconPanel = JLabel()

    var commentTree = JTree()
    var commentTreeRoot = DefaultMutableTreeNode()
    var commentsProgressBar = JProgressBar()
    var loadedCommentCount = 0

    val jfxPanel = JFXPanel()

    var activeStory: Story? = null
    var viewingComments = true

    init {

        val frame = JFrame("Hacker News")
        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val frameSize = Dimension((screenSize.width*0.90f).toInt(), (screenSize.height*0.85f).toInt())
        frame.size = frameSize

        frame.contentPane = contentPane
        contentPane.preferredSize = frameSize

        contentPane.layout = GridBagLayout()
        val c = GridBagConstraints()
        c.anchor = GridBagConstraints.CENTER

        progressBar.preferredSize = Dimension(400, 25)
        progressBar.isStringPainted = true

        //Just add so the display updates. Not sure why, but
        //it delays showing the progress bar if we don't do this.
        contentPane.add(JLabel(""), c)

        c.gridy = 1
        contentPane.add(progressBar, c)
        progressBar.value = 0

        frame.setLocation(screenSize.width / 2 - frame.size.width / 2, screenSize.height / 2 - frame.size.height / 2)
        frame.pack()

        frame.isVisible = true
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE

        val stories = getFrontPageStories()

        contentPane = JPanel(BorderLayout())
        frame.contentPane = contentPane

        val mainPanel = getMainPanel(stories)
        contentPane.add(mainPanel)

        storyList.selectedIndex = 0
        storyList.setCellRenderer(StoryCellRenderer())

        contentToggleButton.addActionListener {
            if (activeStory != null) {
                if (viewingComments) {
                    showPage(activeStory)
                } else {
                    showComments(activeStory)
                }
            }
        }

        frame.revalidate()

        PubSub.subscribe(PubSub.STORY_ICON_LOADED, this)

        styleComponents()
    }

    fun styleComponents() {
        this.storyURLLabel.foreground = Color(100, 100, 100)
        this.storyList.background = Color(242, 242, 242)

        //Dimensions here are somewhat arbitrary, but if we want
        //equal priority given to both splitpane panels, they each
        //need minimum sizes
        mainRightPane.minimumSize = Dimension(300, 200)
    }

    fun getMainPanel(storyNodes: ArrayList<JsonObject>) : JComponent {
        val stories = storyNodes
                .filter { it.get("type").asString.equals("story", true) }
                .map { Story(it) }

        val def = DefaultListCellRenderer()
        val renderer = ListCellRenderer<Story> { list, value, index, isSelected, cellHasFocus ->
            def.getListCellRendererComponent(list, value.title, index, isSelected, cellHasFocus)
        }

        storyList.cellRenderer = renderer
        storyList.addListSelectionListener { e: ListSelectionEvent? ->
            if (e != null) {
                if (!e.valueIsAdjusting) {
                    changeActiveStory(stories[storyList.selectedIndex])
                }
            }
        }

        for (story in stories) {
            listModel.addElement(story)
        }

        mainRightPane = buildMainRightPanel()

        val storyScroller = JScrollPane(storyList)
        storyScroller.minimumSize = Dimension(200, 200)
        storyScroller.verticalScrollBar.unitIncrement = 16

        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, storyScroller, mainRightPane)
        splitPane.isOneTouchExpandable = true

        return splitPane
    }

    fun changeActiveStory(story: Story) {
        activeStory = story

        userLabel.text = "Posted by: ${story.user}"
        pointsLabel.text = "Points: ${story.points}"
        storyTimeLabel.text = "Time: ${story.time}"
        commentCountLabel.text = "Comments: ${story.totalComments}"
        if (story.url != null) {
            storyURLLabel.text = story.url
        }

        showComments(activeStory)
    }

    fun showComments(story: Story?) {
        if (story != null) {
            viewingComments = true
            contentToggleButton.text = "View Page"

            storyPanel.removeAll()
            storyPanel.add(getCommentsPanel(story))
            if (story.bigIcon != null) {
                storyIconPanel.icon = ImageIcon(story.bigIcon)
            } else {
                storyIconPanel.icon = story.favicon
            }
            storyIconPanel.preferredSize = Dimension(storyIconPanel.icon.iconWidth+6, storyIconPanel.icon.iconHeight)
            storyIconPanel.border = BorderFactory.createEmptyBorder(0, 3, 0, 3)
            headlinePanel.preferredSize = Dimension(storyControlPanel.size.width, Math.max(32, storyIconPanel.icon.iconHeight))
            loadComments(story)
        }
    }

    fun showPage(story: Story?) {
        if (story != null) {
            viewingComments = false
            contentToggleButton.text = "View Comments"
            cancelCommentLoading()

            storyPanel.removeAll()
            storyPanel.add(jfxPanel)

            Platform.runLater {
                val webView = WebView()
                jfxPanel.scene = Scene(webView)
                webView!!.engine.load(story.url)
            }

            storyPanel.revalidate()
        } else {
            println("Tried showing page but story was null")
        }
    }

    override fun messageArrived(eventName: String, data: Any?) {
        if (eventName == PubSub.STORY_ICON_LOADED) {
            storyList.repaint()
            storyControlPanel.repaint()
        }
    }

    fun loadComments(story: Story) {
        if (story.totalComments == 0)
            return

        Thread(Runnable {
            if (story.kids != null) {
            var index = 0
            val iter = story.kids.iterator()
            while(iter.hasNext() && viewingComments) {
                val commentId = iter.next().asInt
                val address = "$index"
                loadCommentAux(Comment(getItem(commentId), address), address)
                index++
            }
        } }).start()
    }

    fun loadCommentAux(comment: Comment, treeAddress: String) {
        commentArrived(comment, treeAddress)

        if (comment.kids != null) {
            var index = 0
            val iter = comment.kids.iterator()
            while(iter.hasNext() && viewingComments) {
                val commentId = iter.next().asInt
                val address = treeAddress+";$index"
                loadCommentAux(Comment(getItem(commentId), address), address)
                index++
            }
        }
    }

    fun commentArrived(comment: Comment, treeAddress: String) {
        if (!viewingComments)
            return

        addNodeAtAddress(DefaultMutableTreeNode(comment), comment.treeAddress)

        commentsProgressBar.value = ++loadedCommentCount

        if (loadedCommentCount >= commentsProgressBar.maximum) {
            val parent = commentsProgressBar.parent
            if (parent != null) {
                parent.remove(commentsProgressBar)
                parent.revalidate()
            }
        }
    }

    fun cancelCommentLoading() {
        loadedCommentCount = commentsProgressBar.maximum
    }

    fun addNodeAtAddress(node: DefaultMutableTreeNode, address: String) {
        if (!viewingComments)
            return

        val addressArray = address.split(";")
        var parentNode = commentTreeRoot
        for ((index, addressComponent) in addressArray.withIndex()) {

            // Don't use the last component in the addressArray since that's the index
            // which the child should be added at
            if (index < addressArray.size - 1) {
                val childIndex = Integer.parseInt("$addressComponent")
                if (childIndex < parentNode.childCount) {
                    parentNode = parentNode.getChildAt(childIndex) as DefaultMutableTreeNode
                }
            }

        }

        val childIndex = Integer.parseInt(addressArray[addressArray.size-1])
        (commentTree.model as DefaultTreeModel).insertNodeInto(node, parentNode, childIndex)
    }

    fun getCommentsPanel(story: Story) : JPanel {
        loadedCommentCount = 0

        val panel = JPanel(BorderLayout())
        commentTreeRoot = DefaultMutableTreeNode("Comments")
        commentTree = JTree(commentTreeRoot)
        commentTree.toggleClickCount = 1
        commentTree.cellRenderer = CommentCellRenderer()
        commentTree.model.addTreeModelListener(ExpandTreeListener(commentTree))
        val treeScroller = JScrollPane(commentTree)
        treeScroller.verticalScrollBar.unitIncrement = 16
        treeScroller.horizontalScrollBar.unitIncrement = 16
        if (!(UIManager.getLookAndFeel() is MetalLookAndFeel)) {
            treeScroller.background = Color(242, 242, 242)
            commentTree.background = Color(242, 242, 242)
        }
        panel.add(treeScroller, BorderLayout.CENTER)

        var totalComments = 0

        if (story.totalComments != null) {
            totalComments = story.totalComments
        }

        commentsProgressBar = JProgressBar()
        commentsProgressBar.minimum = 0
        commentsProgressBar.maximum = totalComments
        panel.add(commentsProgressBar, BorderLayout.NORTH)

        return panel
    }

    fun buildMainRightPanel() : JPanel {
        val root = JPanel()
        root.layout = BorderLayout()

        storyControlPanel = JPanel(GridLayout(1, 1))

        storyPanel = JPanel(BorderLayout())

        root.add(storyControlPanel, BorderLayout.NORTH)

        headlinePanel = JPanel(BorderLayout())
        headlinePanel.border = BorderFactory.createEmptyBorder(3, 3, 3, 3)
        headlinePanel.add(storyIconPanel, BorderLayout.WEST)
        headlinePanel.add(storyURLLabel, BorderLayout.CENTER)
        headlinePanel.add(contentToggleButton, BorderLayout.EAST)
        storyControlPanel.add(headlinePanel)

        root.add(storyPanel, BorderLayout.CENTER)

        return root
    }

    fun getFrontPageStories() : ArrayList<JsonObject> {
        val storyIDs = treeFromURL("https://hacker-news.firebaseio.com/v0/topstories.json")
        val iter = storyIDs.asJsonArray.iterator()
        val stories = ArrayList<JsonObject>()

        var count = 0
        while (iter.hasNext()) {
            val id = (iter.next() as JsonPrimitive).asInt

            val story = getItem(id)

            stories.add(story.asJsonObject)
            count++
            progressBar.value = count

            if (count > 29)
                break
        }

        return stories
    }

    fun getItem(id: Int) : JsonObject {
        val item = treeFromURL("https://hacker-news.firebaseio.com/v0/item/$id.json")

        return item.asJsonObject
    }

    fun treeFromURL(url: String) : JsonElement {
        val url = URL(url)
        val connection = (url.openConnection()) as HttpsURLConnection

        val reader = InputStreamReader(connection?.inputStream)

        val parser = JsonParser()
        val element = parser.parse(reader)

        reader.close()

        return element
    }

    class StoryCellRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JComponent

            this.border = BorderFactory.createCompoundBorder(BorderFactory.createEtchedBorder(), BorderFactory.createEmptyBorder(3, 3, 3, 3))
            if (!isSelected && !cellHasFocus) {
                this.background = Color(242, 242, 242)
            }
            this.icon = (value as Story).favicon
            this.text = "<html><span style=\"color: #333333; font-size: 10px\">${value.title}</span><br><span style=\"color: #777777; font-size:8px;\"> ${value.points} points by ${value.user} ${value.time} | ${value.totalComments} comments</span></html>"

            return this
        }
    }

    class CommentCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(tree: JTree?, value: Any?, sel: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean): Component? {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)

            if ((value as DefaultMutableTreeNode).userObject !is Comment) {
                return JLabel()
            }

            val comment = value.userObject as Comment

            var originalCommentText = comment.text
            if (originalCommentText == null)
                originalCommentText = ""

            val panel = JPanel()
            panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
            panel.background = Color(225, 225, 235)

            val metaDataLabel = JLabel(comment.user + " " + comment.time)
            metaDataLabel.foreground = Color(60, 60, 60)
            metaDataLabel.font = Font("Arial", Font.PLAIN, 11)
            metaDataLabel.border = BorderFactory.createEmptyBorder(3, 3, 3, 3)

            panel.add(metaDataLabel)

            //Insert linebreaks periodically since this seems to be the only way to limit
            //the width of the html rendering JTextPane without also limiting the height
            var text = ""
            var index = 0
            val words = originalCommentText.split(" ")
            for (word in words) {
                if (word.indexOf("<br>") != -1 || word.indexOf("</p>") != -1 || word.indexOf("</pre>") != -1) {
                    index = 0
                } else if (index > 20) {
                    index = 0
                    text += "<br>"
                }
                text += " " + word

                index++
            }

            val textPane = JTextPane()
            textPane.contentType = "text/html"
            textPane.isEditable = false
            val doc = textPane.document as HTMLDocument
            val editorKit = textPane.editorKit as HTMLEditorKit
            editorKit.insertHTML(doc, doc.length, text, 0, 0, null)
            textPane.background = Color(242, 242, 255)
            textPane.border = BorderFactory.createCompoundBorder(BorderFactory.createEtchedBorder(), BorderFactory.createEmptyBorder(5, 5, 5, 5))
            textPane.minimumSize = Dimension(textPane.size.width, textPane.size.height)

            panel.add(textPane)

            return panel
        }
    }

    class ExpandTreeListener(theTree: JTree) : TreeModelListener {

        val tree = theTree

        override fun treeStructureChanged(e: TreeModelEvent?) {
            println("[FaviconFetcher] Failed to download icon: ")
        }

        override fun treeNodesChanged(e: TreeModelEvent?) {
            println("[FaviconFetcher] Failed to download icon: ")
        }

        override fun treeNodesRemoved(e: TreeModelEvent?) {
            println("[FaviconFetcher] Failed to download icon: ")
        }

        override fun treeNodesInserted(e: TreeModelEvent?) {
            if (e != null) {
                if (e.treePath.pathCount == 1) {
                    this.tree.expandPath(e.treePath)
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    App()
}
