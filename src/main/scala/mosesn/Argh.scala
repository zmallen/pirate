package mosesn

import scala.util.parsing.input.CharSequenceReader
import scala.util.parsing.combinator.JavaTokenParsers

object Argh extends JavaTokenParsers {

  val emptyCharSequenceReader = new CharSequenceReader("")

  val HyphenParser: Parser[String] = "-"

  val WhitespaceParser: Parser[String] = """\s*""".r

  val OptionsParser: String => Parser[Arguments] = (flags: String) => (HyphenParser ~>
      OptFlagsParser(flags)) map (list => Arguments.empty.copy(flags = list.toSet))

  val OptFlagParser: String => Parser[Elem] = (flags: String) =>
    acceptIf(flags.contains(_))((err: Char) => "%c is not a valid flag.".format(err))

  val OptFlagsParser: String => Parser[List[Char]] = (flags: String) => rep1(OptFlagParser(flags))

  val FlagsParser: Parser[String] = """[A-Za-z]+""".r

  val LetterParser = acceptIf(_.isLetter)((err: Char) => "%c should have been a letter.".format(err))

  val AllFlagsParser: Parser[Parser[Arguments]] = FlagsParser map (OptionsParser(_))

  val BeginBracketParser = elem('[') <~ WhitespaceParser

  val EndBracketParser = WhitespaceParser ~> elem(']')

  def OptionMultiArgSchema: Parser[Parser[Arguments]] =
    (BeginBracketParser ~> MultiArgSchema <~ EndBracketParser) map (opt(_) map (_ getOrElse Arguments.empty))

  val int = "int"

  val double = "double"

  val string = "string"

  lazy val Innteger: Parser[Int] = wholeNumber map (_.toInt)

  lazy val Doouble: Parser[Double] = floatingPointNumber map (_.toDouble)

  val DotParser = elem('.')

  lazy val IntParser: Char => Parser[Arguments] =
    char => HyphenParser ~> elem(char) ~> WhitespaceParser ~> Innteger map { elt =>
      Arguments.empty.copy(intMap = Map(char -> elt))
    }

  lazy val DoubParser: Char => Parser[Arguments] =
    char => HyphenParser ~> elem(char) ~> WhitespaceParser ~> Doouble map { elt =>
      Arguments.empty.copy(doubleMap = Map(char -> elt))
    }

  val WhiteSpacedInt = WhitespaceParser ~ int
  val WhiteSpacedDouble = WhitespaceParser ~ double
  val WhiteSpacedString = WhitespaceParser ~ string

  lazy val NamedStringParser: (String => Parser[Arguments]) = (name: String) => """[^\s]+""".r map { string =>
    Arguments.empty.copy(strings = Map(name -> string))
  }

  lazy val StringSchema: Parser[Parser[Arguments]] = """\w+""".r map { key =>
    NamedStringParser(key)
  }

  lazy val FlexibleStringSchema: Parser[Parser[Arguments]] = OptStringSchema | StringSchema

  lazy val MultiStringSchema: Parser[Parser[Arguments]] = (FlexibleStringSchema ~ WhitespaceParser ~ MultiStringSchema map {
    case single ~ _ ~ many => single ~ many map (arg => arg._1 + arg._2)
  }) | FlexibleStringSchema

  lazy val OptStringSchema: Parser[Parser[Arguments]] =
    BeginBracketParser ~> MultiStringSchema <~ EndBracketParser map {
      opt(_) map (_.getOrElse(Arguments.empty))
    }

  lazy val IntegerParser: Parser[Parser[Arguments]] = (acceptIf(_.isLetter)((err: Char) => "%c should have been a letter".format(err)) <~ WhiteSpacedInt) map (arg => IntParser(arg))

  lazy val StringParser: Parser[Parser[Arguments]] = (acceptIf(_.isLetter)((err: Char) =>
    "%c should have been a letter".format(err)) <~ WhiteSpacedString) map (arg => "-" ~> arg.toString ~> WhitespaceParser ~>
        NamedStringParser(arg.toString))

  lazy val DoubleParser = (acceptIf(_.isLetter)((err: Char) => "%c should have been a letter".format(err)) <~ WhiteSpacedDouble) map (DoubParser(_))

  lazy val NumberParser = DoubleParser | IntegerParser

  lazy val HyphenStartedSchema: Parser[Parser[Arguments]] = HyphenParser ~> (NumberParser | AllFlagsParser)

  val ArgSchema: Parser[Parser[Arguments]] = (OptionMultiArgSchema | HyphenStartedSchema)

  def MultiArgSchema: Parser[Parser[Arguments]] = handleMultiArgsFancy(tmp)

  def tmp = repsep(ArgSchema, WhitespaceParser)

  def handleMultiArgs(top: Parser[List[Parser[Arguments]]]): Parser[Parser[Arguments]] =
    top map {parsers =>
      parsers reduceOption {(first, second) =>
        (first ~ second) map (arg => arg._1 + arg._2)
      } getOrElse success(Arguments.empty)
    }

  def disableOption(parser: Parser[Arguments]): Parser[Arguments] = Parser { input =>
    parser(input) match {
      case s @ Success(argument, _) => if (argument.isEmpty) Failure("no empties", input) else s
      case noSuccess => noSuccess
    }
  }

  def handleMultiArgsFancy(top: Parser[List[Parser[Arguments]]]): Parser[Parser[Arguments]] =
    top map {list =>
      andWithRepetition(Nil, list,
        (first: Arguments, second: Arguments) => first + second,
        Arguments.empty, disableOption)
    }

  def andWithRepetition[T](parsed: List[Parser[T]],
    unparsed: List[Parser[T]],
    reducer: (T, T) => T,
    default: T,
    disEnabler: Parser[T] => Parser[T]): Parser[T] =
      andWithRepetition(ParserHelper(parsed, unparsed, reducer, default, disEnabler))

  private[this] def applyReducer[T](concat: ~[T, T], reducer: (T, T) => T) = concat match {
    case f ~ s => reducer(f, s)
  }

  case class ParserHelper[T](parsed: List[Parser[T]],
    unparsed: List[Parser[T]],
    reducer: (T, T) => T,
    default: T,
    disEnabler: Parser[T] => Parser[T]) {
    def useParser(parser: Parser[T]) = this.copy(parsed = disEnabler(parser) :: this.parsed,
      unparsed = this.unparsed filterNot (_ == parser))
  }

  object ParserHelper {
    def apply[T](list: List[Parser[T]],
      reducer: (T, T) => T,
      default: T,
      disEnabler: Parser[T] => Parser[T]): ParserHelper[T] =
        ParserHelper(List(), list, reducer, default, disEnabler)
  }

  private[this] def andWithRepetition[T](helper: ParserHelper[T]): Parser[T] =
    Pair(helper.unparsed, helper.parsed) match {
      case (Nil, Nil) => success(helper.default)
      case (Nil, list) => rep(list reduce (_ | _)) map {arg =>
        arg reduceOption (helper.reducer(_, _)) getOrElse helper.default
      }
      case (_, Nil) => parseUnparsed(helper)
      case (_, _) => parseUnparsed(helper) | parseParsed(helper)
    }

  private[this] def parseParsed[T](helper: ParserHelper[T]) =
    (helper.parsed reduce (_ | _)) ~ andWithRepetition(helper) map {
      applyReducer(_, helper.reducer)
    }

  private[this] def parseUnparsed[T](helper: ParserHelper[T]) =
    (helper.unparsed map {parser =>
    parser ~ andWithRepetition(helper.useParser(parser)) map (
      applyReducer(_, helper.reducer)
    )
  }) reduce (_ | _)


  lazy val OptionArgSchema: Parser[Parser[Arguments]] =
    BeginBracketParser ~> ArgSchema <~ EndBracketParser map {arg =>
      opt(arg) map {
        case Some(args) => args
        case None => Arguments.empty
      }
    }

  def parseAnd(parsed: Set[Parser[Arguments]], unparsed: Set[Parser[Arguments]] ):
    Parser[Arguments] = {
      def orEverything(set: Set[Parser[Arguments]]) = set reduceOption (_ | _)
      def addEverything(list: List[Arguments]) = list reduceOption (_ + _)

      if (unparsed.isEmpty) {
        (orEverything(parsed) map (rep(_) map {
          addEverything(_) getOrElse (Arguments.empty)
        })) getOrElse success(Arguments.empty)
      }
      else {
        def continue(parser: Parser[Arguments],
            newParsed: Set[Parser[Arguments]],
            newUnparsed: Set[Parser[Arguments]]) = {
          parser ~ parseAnd(newParsed, newUnparsed) map {
            case arguments ~ result => arguments + result
          }
        }
        val removedParser = unparsed map {parser =>
          continue(parser, parsed + parser, unparsed - parser)} reduce (_ | _)

        if (!parsed.isEmpty) {
          val repeatParser = continue(parsed reduce (_ | _), parsed, unparsed)
          (removedParser | repeatParser)
        }
        else removedParser
      }
    }

  def PhrasedMAS = phrase(MultiArgSchema)

  lazy val FlagsAndStringsSchema: Parser[Parser[Arguments]] = opt(MultiArgSchema) ~ WhitespaceParser ~ opt(MultiStringSchema) map {
    case Some(args) ~ _ ~ Some(flags) => args ~ flags map { concat => concat._1 + concat._2 }
    case Some(args) ~ _ ~ None => args
    case None ~ _ ~ Some(flags) => flags
    case None ~ _ ~ None => success(Arguments.empty)
  }

  lazy val PhraseFASSchema: Parser[Parser[Arguments]] = phrase(FlagsAndStringsSchema)

  def apply(helpText: String)(flags: Array[String]): Arguments = PhrasedMAS(new CharSequenceReader(helpText)) match {
    case Success(parser, _) => parser(new CharSequenceReader(flags mkString " ")) match {
      case Success(results, _) => results
      case failure: NoSuccess => scala.sys.error(failure.msg)
    }
    case failure: NoSuccess => scala.sys.error(failure.msg)
  }

}

case class Arguments(flags: Set[Char], intMap: Map[Char, Int], doubleMap: Map[Char, Double], strings: Map[String, String]) {
  def addFlag(flag: Char): Arguments = this.copy(flags = flags + flag)

  def +(args: Arguments): Arguments = Arguments(this.flags ++ args.flags, this.intMap ++ args.intMap, this.doubleMap ++ args.doubleMap, this.strings ++ args.strings)

  def isEmpty = this == Arguments.empty
}

object Arguments {
  lazy val empty = Arguments(Set.empty[Char], Map.empty[Char, Int], Map.empty[Char, Double], Map.empty[String, String])
}