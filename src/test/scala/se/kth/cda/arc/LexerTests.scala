package se.kth.cda.arc

import java.util.BitSet

import org.antlr.v4.runtime._
import org.antlr.v4.runtime.atn.ATNConfigSet
import org.antlr.v4.runtime.dfa.DFA
import org.scalatest._

import scala.language.implicitConversions

sealed trait TokenComparator {
  def ===(t: Token): Boolean

  def display(v: Vocabulary): String
}
final case class TypeComparator(tokenType: Int) extends TokenComparator {
  override def ===(t: Token): Boolean = t.getType == tokenType

  override def display(v: Vocabulary): String = v.getDisplayName(tokenType)
}
final case class TextComparator(tokenText: String) extends TokenComparator {
  override def ===(t: Token): Boolean = t.getText == tokenText

  override def display(v: Vocabulary): String = tokenText
}
final case class FullComparator(tokenType: Int, tokenText: String) extends TokenComparator {
  override def ===(t: Token): Boolean = (t.getType == tokenType) && (t.getText == tokenText)

  override def display(v: Vocabulary): String = s"${v.getDisplayName(tokenType)}($tokenText)"
}
class LexerTests extends FunSuite with Matchers {

  implicit def intToTypeComp(i: Int): TokenComparator = TypeComparator(i)

  implicit def strToTextComp(s: String): TokenComparator = TextComparator(s)

  implicit class IntCompAux(val i: Int) {
    def apply(s: String): TokenComparator = FullComparator(i, s)
  }

  implicit class StringTokenAux(val input: String) {
    def ===(tokenIds: Array[TokenComparator]): Unit = {
      val inputStream = CharStreams.fromString(input)
      val lexer = new ArcLexer(inputStream)
      val errorC = new ErrorCounter()
      lexer.addErrorListener(errorC)
      val tokenStream = new CommonTokenStream(lexer)
      tokenStream.fill()
      assert(!errorC.hasErrors, "Lexer encountered a syntax error!")
      val tokenList = tokenStream.getTokens
      val tokens = tokenList.toArray(new Array[Token](tokenList.size()))
      val vocab = lexer.getVocabulary
      var tokenPos = 0
      var idPos = 0
      while (tokenPos < tokens.length) {
        val token = tokens(tokenPos)
        if (token.getChannel == Lexer.DEFAULT_TOKEN_CHANNEL) {
          assert(idPos < tokenIds.length, s"Expected no input, but got $token.")
          val expected = tokenIds(idPos)
          assert(expected === token, s"Expected ${expected.display(vocab)}, but got $token at position $idPos.")
          idPos += 1
        }
        tokenPos += 1
      }
    }

    def isInvalid(): Unit = {
      val inputStream = CharStreams.fromString(input)
      val lexer = new ArcLexer(inputStream)
      val errorC = new ErrorCounter()
      lexer.addErrorListener(errorC)
      val tokenStream = new CommonTokenStream(lexer)
      tokenStream.fill()
      assert(errorC.hasErrors, s"Expected an error but tokenized fine to:\n${tokenStream.getTokens}")
    }
  }

  test("basic token types") {
    import ArcLexer._
    import Token.EOF

    """"test string"""" === Array(TStringLit, EOF)
    """"test" string""" === Array(TStringLit, TIdentifier, EOF)

    "a for 23 + z0" === Array(TIdentifier, TFor, TI32Lit, TPlus, TIdentifier, EOF)

    "= == | || & &&" === Array(TEqual, TEqualEqual, TBar, TBarBar, TAnd, TAndAnd, EOF)
    "|t| !t" === Array(TBar, TIdentifier, TBar, TBang, TIdentifier, EOF)
    "|a:i8| a" === Array(TBar, TIdentifier, ":", TI8, TBar, TIdentifier, EOF)

    //"(a)".isInvalid; // temporarily until the parser introduces parens

    "42si" === Array(TI16Lit, EOF)
    "0b10" === Array(TI32Lit, EOF)
    "0x10" === Array(TI32Lit, EOF)

    "1e-5f" == Array(TF32Lit, EOF)
    "1e-5" == Array(TF64Lit, EOF)
  }
}

class ErrorCounter extends ANTLRErrorListener {
  private var errors: Int = 0

  def hasErrors: Boolean = errors > 0

  override def syntaxError(
    recognizer:         Recognizer[_, _],
    offendingSymbol:    Object,
    line:               Int,
    charPositionInLine: Int,
    msg:                String,
    e:                  RecognitionException): Unit = {
    errors += 1
  }

  override def reportAmbiguity(
    recognizer: Parser,
    dfa:        DFA,
    startIndex: Int,
    stopIndex:  Int,
    exact:      Boolean,
    ambigAlts:  BitSet,
    configs:    ATNConfigSet): Unit = ()

  override def reportAttemptingFullContext(
    recognizer:      Parser,
    dfa:             DFA,
    startIndex:      Int,
    stopIndex:       Int,
    conflictingAlts: BitSet,
    configs:         ATNConfigSet): Unit = ()

  override def reportContextSensitivity(
    recognizer: Parser,
    dfa:        DFA,
    startIndex: Int,
    stopIndex:  Int,
    prediction: Int,
    configs:    ATNConfigSet): Unit = ()
}
