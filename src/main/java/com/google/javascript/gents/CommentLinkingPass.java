package com.google.javascript.gents;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.javascript.jscomp.Compiler;
import com.google.javascript.jscomp.CompilerPass;
import com.google.javascript.jscomp.NodeTraversal;
import com.google.javascript.jscomp.NodeTraversal.Callback;
import com.google.javascript.jscomp.parsing.parser.trees.Comment;
import com.google.javascript.jscomp.parsing.parser.trees.Comment.Type;
import com.google.javascript.rhino.InputId;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Links comments directly to the AST to preserve locations in file */
public final class CommentLinkingPass implements CompilerPass {
  /** Regex matcher for all 3 empty comment types */
  private static final Pattern EMPTY_COMMENT_REGEX =
      Pattern.compile("^\\s*(\\/\\/|\\/\\*(\\s|\\*)*\\*\\/)\\s*$");

  /** Regex fragment that optionally matches the beginning of a JSDOC line. */
  private static final String BEGIN_JSDOC_LINE = "(?<block>[ \t]*\\*[ \t]*)?";

  /** Regex fragment to optionally match end-of-line */
  private static final String EOL = "(?<eol>[ \t]*\n)?";

  /**
   * These RegExes delete everything except for the `block` (see BEGIN_JSDOC_LINE) and `keep`
   * capture groups. These RegExes must contain a `keep` group.
   *
   * <p>TODO(b/151088265) Simplify regex.
   */
  private static final Pattern[] JSDOC_REPLACEMENTS_WITH_KEEP = {
    // Removes @param and @return if there is no description
    Pattern.compile(
        BEGIN_JSDOC_LINE + "@param[ \t]*(\\{[^@]*\\})[ \t]*[\\w\\$]+[ \t]*(?<keep>\\*\\/|\n)"),
    Pattern.compile(BEGIN_JSDOC_LINE + "@returns?[ \t]*(\\{.*\\})[ \t]*(?<keep>\\*\\/|\n)"),
    Pattern.compile(BEGIN_JSDOC_LINE + "(?<keep>@(param|returns?))[ \t]*(\\{.*\\})"),
    // Remove type annotation from @export
    Pattern.compile(BEGIN_JSDOC_LINE + "(?<keep>@export)[ \t]*(\\{.*\\})"),
  };

  /**
   * These RegExes delete everything that's matched by them, they must begin with BEGIN_JSDOC_LINE
   * and finish with EOL.
   */
  private static final Pattern[] JSDOC_REPLACEMENTS_NO_KEEP = {
    Pattern.compile(BEGIN_JSDOC_LINE + "@(extends|implements|type)[ \t]*(\\{[^@]*\\})[ \t]*" + EOL),
    Pattern.compile(BEGIN_JSDOC_LINE + "@(constructor|interface|record)[ \t]*" + EOL),
    Pattern.compile(
        BEGIN_JSDOC_LINE
            + "@(private|protected|public|package|const|enum)[ \t]*(\\{.*\\})?[ \t]*"
            + EOL),
    Pattern.compile(BEGIN_JSDOC_LINE + "@suppress[ \t]*\\{extraRequire\\}[ \t]*" + EOL),
    // Remove @typedef if there is no description.
    Pattern.compile(BEGIN_JSDOC_LINE + "@typedef[ \t]*(\\{.*\\})" + EOL, Pattern.DOTALL),
    // Remove @abstract.
    Pattern.compile(BEGIN_JSDOC_LINE + "@abstract" + EOL, Pattern.DOTALL)
  };

  private static final Pattern[] COMMENT_REPLACEMENTS = {Pattern.compile("//\\s*goog.scope\\s*")};

  /**
   * These nodes can expand their children with new EMPTY nodes. We will use that as a placeholder
   * for floating comments.
   */
  private static final ImmutableSet<Token> TOKENS_CAN_HAVE_EMPTY_CHILD =
      ImmutableSet.of(Token.SCRIPT, Token.MODULE_BODY, Token.BLOCK);
  /**
   * Comments assigned to these nodes will not be emitted during code generation because these nodes
   * don't use add(Node n, Context ctx). Therefore we avoid assigning comments to them.
   */
  // TODO(bowenni): More nodes missing here?
  private static final ImmutableSet<Token> TOKENS_IGNORE_COMMENTS =
      ImmutableSet.of(Token.LABEL_NAME, Token.GENERIC_TYPE, Token.BLOCK);

  private final Compiler compiler;
  private final NodeComments nodeComments;

  CommentLinkingPass(Compiler compiler) {
    this.compiler = compiler;
    this.nodeComments = new NodeComments();
  }

  public NodeComments getComments() {
    return nodeComments;
  }

  @Override
  public void process(Node externs, Node root) {
    for (Node script : root.children()) {
      if (script.isScript()) {
        // Note: this doesn't actually copy the list since the underlying list is already an
        // immutable list.
        ImmutableList<Comment> comments =
            ImmutableList.copyOf(compiler.getComments(script.getSourceFileName()));
        NodeTraversal.traverse(compiler, script, new LinkCommentsForOneFile(comments));
      }
    }
  }

  /**
   * Links all the comments in one file to the AST.
   *
   * <p>Comments are grouped based on location in the source file. Adjacent comments are grouped
   * together in order to assure that the we do not break up the same coherent thought. Comment
   * groups are separated based on empty lines or lines of code.
   */
  private class LinkCommentsForOneFile implements Callback {
    /** List of all comments in the file */
    private final ImmutableList<Comment> comments;
    /** Collects of all comments that are grouped together. */
    private List<Comment> commentBuffer = new ArrayList<>();

    private int lastCommentIndex = 0;

    private LinkCommentsForOneFile(ImmutableList<Comment> comments) {
      this.comments = comments;
      if (!comments.isEmpty()) {
        commentBuffer.add(comments.get(0));
      }
    }

    /** Returns if we have finished linking all comments in the current file. */
    private boolean isDone() {
      return lastCommentIndex >= comments.size();
    }

    /** Returns if there are comments in the input that we have not looked at yet. */
    private boolean hasRemainingComments() {
      return lastCommentIndex < comments.size() - 1;
    }

    private Comment getCurrentComment() {
      return comments.get(lastCommentIndex);
    }

    private Comment getNextComment() {
      return comments.get(lastCommentIndex + 1);
    }

    /** Returns the ending line number of the current comment. */
    private int getLastLineOfCurrentComment() {
      return getCurrentComment().location.end.line + 1;
    }

    /** Returns the starting line number of the next comment. */
    private int getFirstLineOfNextComment() {
      return getNextComment().location.start.line + 1;
    }

    /** Shifts a new comment into the buffer. */
    private void addNextCommentToBuffer() {
      if (hasRemainingComments()) {
        commentBuffer.add(getNextComment());
      }
      lastCommentIndex++;
    }

    /** Flushes the comment buffer, linking it to the provided node. */
    private void linkCommentBufferToNode(Node n) {
      if (!canHaveComment(n)) {
        linkCommentBufferToNode(n.getParent());
        return;
      }
      StringBuilder sb = new StringBuilder();
      String sep = "\n";
      for (Comment c : commentBuffer) {
        String comment = filterCommentContent(c.type, c.value);
        if (!comment.isEmpty()) {
          sb.append(sep).append(comment);
        }
      }

      String comment = sb.toString();
      if (!comment.isEmpty()) {
        nodeComments.addComment(n, comment);
      }
      commentBuffer.clear();
    }

    /** Removes unneeded tags and markers from the comment. */
    private String filterCommentContent(Type type, String comment) {
      if (type == Type.JSDOC) {
        comment = preprocessJsDocComment(comment);
        for (Pattern p : JSDOC_REPLACEMENTS_WITH_KEEP) {
          Matcher m = p.matcher(comment);
          if (m.find() && m.group("keep") != null && m.group("keep").trim().length() > 0) {
            // keep documentation, if any
            comment = m.replaceAll("${block}${keep}");
          } else {
            // nothing to keep, remove the line
            comment = m.replaceAll("");
          }
        }

        for (Pattern p : JSDOC_REPLACEMENTS_NO_KEEP) {
          Matcher m = p.matcher(comment);
          if (m.find()) {
            if (m.group("eol") != null && m.group("eol").trim().length() == 0) {
              // if the end of the line was matched, then there's nothing to keep, remove the line
              comment = m.replaceAll("");
            } else {
              // If something is still left on the line after the match was removed, keep
              // `block` around since it matches the comment * for the beginning of the line.
              comment = p.matcher(comment).replaceAll("${block}");
            }
          }
        }
      } else {
        for (Pattern p : COMMENT_REPLACEMENTS) {
          comment = p.matcher(comment).replaceAll("");
        }
      }

      return isWhitespaceOnly(comment) ? "" : comment;
    }

    /**
     * Per b/145684409, Gents used to throw away JSDoc annotations whose explanatory text was on
     * lines following JSDoc annotation, making it unclear what the explanitory text refers to. It
     * did this because the `filterCommentContent()` function above assumes JSDoc comments and their
     * explanatory text will always be on the same line.
     *
     * <p>Rather than try to improve the implementation of `filterCommentContent()`, the following
     * function, `preprocessJsDocComments()` was added. It takes a JSDoc Comment and returns it with
     * all explanatory text on the same line. (Note: Auto-formatter will run afterwards, cleaning up
     * line over-runs.)
     *
     * <p>Requires `comment` to be of `Type.JSDOC`.
     */
    private String preprocessJsDocComment(String comment) {
      // b/145684409 only occurs when `comment` spans multiple lines. So, if `comment` isn't
      // multiline, don't modify it.
      if (comment.contains("\n")) {

        // The following regex matches `} \n * Explanation` as follows:
        //
        //   \\}
        //     exactly one right curly bracket.
        //
        //   \\s*
        //     zero or more whitespaces (including tab, newline, etc.).
        //
        //   \\*
        //     exactly one asterisks.
        //
        //   \\s{2,}(?![@,/,\n])
        //     two or more whitespaces (including tab, newline, etc.), except whitespaces
        //     followed by an ampersand, forward slash, or newline.
        //
        //     Negative lookahead disallows the following matches:
        //       `} \n * @`    Type information followed by another JSDoc annotation
        //       `} \n */`     Type information followed by a trailing `*/`
        //       `} \n * \n `  Type information followed by an empty multiline comment row
        //
        //   (?<explanatoryText>\\S+)
        //     one or more non whitespace characters; store matched characters in
        //     `explanatoryText` variable.
        //
        // Replaces match with `} {blah} Explanation`.
        //
        // In other words, `@foo {blah} \n * Explanation` becomes `@foo {blah} Explanation`.
        comment =
            comment.replaceAll(
                "\\}\\s*\\*\\s{2,}(?![@,/,\n])(?<explanatoryText>\\S+)", "} ${explanatoryText}");

        // The following regex matches `} identifier \n * Explanation` as follows:
        //
        //   \\}
        //     exactly one right curly bracket.
        //
        //   \\s+
        //     one or more whitespaces (including tab, newline, etc.).
        //
        //   (?<identifier>\\S+)
        //     one or more non whitespace characters; store matched characters in `identifier`
        //     variable.
        //
        //   \\s*
        //     zero or more whitespaces (including tab, newline, etc.).
        //
        //   \\*
        //     exactly one asterisks.
        //
        //   [^\\S\\n\\r@/]{2,}
        //     Two or more whitespace characters other than newline or line feed. Can't be ampersand
        //     or forward slash, either.
        //
        //     Note: [^\\S] is a double negative equivalent to \\s. The double negative expression
        //           [^\\S\\n\\r] can be read, "The set of \\s minus \\n and \\r".
        //
        //     [^\\S\\n\\r@/]{2,} disallows the following matches:
        //       `} identifier \n * \n `  An identifier followed by an empty multiline comment row
        //       `} identifier \n * @`    An identifier followed by another JSDoc annotation
        //       `} identifier \n */`     An identifier followed by a trailing `*/`
        //
        // Replaces match with `} {blah} identifier Explanation`.
        //
        // In other words, `@foo {blah} identifier \n * Explanation` becomes
        // `@foo {blah} identifier Explanation`.
        comment =
            comment.replaceAll(
                "\\}\\s+(?<identifier>\\S+)\\s*\\*[^\\S\\n\\r@/]{2,}", "} ${identifier} ");
      }
      return comment;
    }

    /** Returns if the comment only contains whitespace. */
    private boolean isWhitespaceOnly(String comment) {
      return EMPTY_COMMENT_REGEX.matcher(comment).find();
    }

    /** Returns a new comment attached to an empty node. */
    private Node newFloatingCommentFromBuffer() {
      Node c = new Node(Token.EMPTY);
      linkCommentBufferToNode(c);
      return c;
    }

    /**
     * If the parent node can survive code generation with an extra EMPTY node child, then output
     * floating comments by attaching to a new EMPTY node before the current node. Otherwise try to
     * put the comment in current node. If the current node doesn't accept comments (for example a
     * BLOCK node will ignore any comments mapped to it), then attach the comments to the parent
     * node. The parent node might not be the best place to hold the comment but at least we don't
     * lose the comment and we don't break code generation.
     */
    private void outputFloatingCommentFromBuffer(Node n) {
      Node parent = n.getParent();
      if (parent == null) {
        linkCommentBufferToNode(n);
      } else if (TOKENS_CAN_HAVE_EMPTY_CHILD.contains(parent.getToken())) {
        parent.addChildBefore(newFloatingCommentFromBuffer(), n);
      } else if (canHaveComment(n)) {
        linkCommentBufferToNode(n);
      } else {
        outputFloatingCommentFromBuffer(parent);
      }
    }

    private boolean canHaveComment(Node n) {
      if (TOKENS_IGNORE_COMMENTS.contains(n.getToken())) {
        return false;
      }

      // Special case: GETPROP node's second child(the property name) loses comments.
      Node parent = n.getParent();
      if (parent != null && parent.isGetProp() && parent.getSecondChild() == n) {
        return false;
      }

      return true;
    }

    /** Returns if the current comment is directly adjacent to a line. */
    private boolean isCommentAdjacentToLine(int line) {
      int commentLine = getLastLineOfCurrentComment();
      return commentLine == line
          || (commentLine == line - 1 && commentLine != getFirstLineOfNextComment());
    }

    @Override
    public final boolean shouldTraverse(NodeTraversal nodeTraversal, Node n, Node parent) {
      if (isDone()) {
        return false;
      }
      // Ignore top level block
      if (n.isScript() || n.isModuleBody()) {
        return true;
      }

      int line = n.getLineno();
      // Comment is AFTER this line
      if (getLastLineOfCurrentComment() > line) {
        return true;
      }

      boolean outputCommentAfterLine = false;
      while (hasRemainingComments() && !isCommentAdjacentToLine(line)) {
        // Comment is AFTER this line
        if (getLastLineOfCurrentComment() > line) {
          return true;
        }

        // If the new comment is separated from the current one by at least a line,
        // output the current group of comments.
        if (getFirstLineOfNextComment() - getLastLineOfCurrentComment() > 1) {
          outputFloatingCommentFromBuffer(n);
        }
        addNextCommentToBuffer();
        outputCommentAfterLine = true;
      }

      if (getLastLineOfCurrentComment() == line) {
        // Comment on same line as code -- we have to make sure this is the node we should attach
        // it to.
        if (parent.isCall()) {
          // We're inside a function call, we have to be careful about which node to attach to,
          // since comments
          // can go before or after an argument.
          if (linkFunctionArgs(n, line)) return true;
        } else if (getCurrentComment().location.end.column < n.getCharno()) {
          // comment is before this node, so attach it
          linkCommentBufferToNode(n);
        } else {
          return true;
        }
      } else if (getLastLineOfCurrentComment() == line - 1) {
        // Comment ends just before code
        linkCommentBufferToNode(n);
      } else if (!hasRemainingComments() && !outputCommentAfterLine) {
        // Exhausted all comments, output floating comment.
        outputFloatingCommentFromBuffer(n);
      }

      if (!outputCommentAfterLine) {
        addNextCommentToBuffer();
      }
      return true;
    }

    private boolean linkFunctionArgs(Node n, int line) {
      if (n.getNext() == null) {
        // the last argument in the call, attach the comment here
        linkCommentBufferToNode(n);
      } else {
        int endOfComment = getCurrentComment().location.end.column;
        int startOfNextNode = n.getNext().getCharno();
        if (endOfComment < startOfNextNode) {
          // the comment is between this node and the next node, so check which side of the comment
          // the comma
          // separating the arguments is on to decide which argument to attach it to.
          String lineContents =
              compiler.getInput(new InputId(n.getSourceFileName())).getSourceFile().getLine(line);
          // nextNode could be on a different line.
          int searchForCommaUntil =
              getCurrentComment().location.end.line == n.getNext().getLineno()
                  ? startOfNextNode
                  : lineContents.length();
          String interval = lineContents.substring(endOfComment, searchForCommaUntil);
          if (interval.contains(",")) {
            linkCommentBufferToNode(n);
          } else {
            linkCommentBufferToNode(n.getNext());
          }
        } else {
          // comment is after this node, keep traversing the node graph
          return true;
        }
      }
      return false;
    }

    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (isDone()) {
        return;
      }
      if (n.isScript()) {
        // New dummy node at the end of the file
        Node dummy = new Node(Token.EMPTY);
        n.addChildToBack(dummy);

        while (hasRemainingComments()) {
          // If the new comment is separated from the current one by at least a line,
          // output the current group of comments.
          if (getFirstLineOfNextComment() - getLastLineOfCurrentComment() > 1) {
            dummy.getParent().addChildBefore(newFloatingCommentFromBuffer(), dummy);
          }
          addNextCommentToBuffer();
        }
        n.addChildBefore(newFloatingCommentFromBuffer(), dummy);
        addNextCommentToBuffer();
        n.removeChild(dummy);
      }
    }
  }
}
