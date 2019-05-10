package dotty.tastydoc

import scala.tasty.Reflection
import dotty.tastydoc.comment.{CommentParser, CommentCleaner, Comment, WikiComment, MarkdownComment}
import dotty.tastydoc.references._

object representations extends CommentParser with CommentCleaner {

  //TODO
  //- reflect as implicit?

  def removeColorFromType(tpe: String) : String = { //TODO: This a workaround, fix this
    tpe.replaceAll("\u001B\\[[;\\d]*m", "")
  }

  trait Representation {
    val name : String
    val path : List[String]
    val comments: Option[Comment]
    val parent = None //TODO: Temporary fix for HTMLParsers
  }

  trait Parents {
    val parent : Option[String]
    val parents : List[Reference]
  }

  trait Members {
    val members : List[Representation]
  }

  trait Modifiers {
    val modifiers: List[String]
    val privateWithin: Option[Reference]
    val protectedWithin: Option[Reference]

    def isPrivate: Boolean = modifiers.contains("private")
    def isProtected: Boolean = modifiers.contains("protected")
  }

  trait Companion {
    def hasCompanion: Boolean = companionPath ne Nil

    val companionPath: List[String]

    // val companionPath_=(xs: List[String]): Unit
  }

  trait ParamList {
    val list: List[NamedReference]
    val isImplicit: Boolean
  }

  trait MultipleParamList {
    val paramLists: List[ParamList]
  }

  trait Constructors {
    val constructors: List[(MultipleParamList, Option[Comment])]
  }

  trait ReturnValue {
    val returnValue: Reference
  }

  trait TypeParams {
    val typeParams: List[String]
  }

  trait Annotations {
    val annotations: List[String]
  }

  private def extractPath(reflect: Reflection)(symbol: reflect.Symbol) : List[String] = {
    import reflect._

    val pathArray = symbol.show.split("\\.")
    pathArray.view(0, pathArray.length - 1).toList
  }

  private def extractModifiers(reflect: Reflection)(flags: reflect.Flags, privateWithin: Option[reflect.Type], protectedWithin: Option[reflect.Type]) : (List[String], Option[Reference], Option[Reference]) = {
    import reflect._

    (((if(flags.is(Flags.Override)) "override" else "") ::
    (if(flags.is(Flags.Private)) "private" else "")::
    (if(flags.is(Flags.Protected)) "protected" else "") ::
    (if(flags.is(Flags.Final)) "final" else "") ::
    (if(flags.is(Flags.Sealed)) "sealed" else "") ::
    (if(flags.is(Flags.Implicit)) "implicit" else "") ::
    (if(flags.is(Flags.Abstract)) "abstract" else "") ::
    Nil) filter (_ != ""),

    privateWithin match {
      case Some(t) => Some(convertTypeToReference(reflect)(t))
      case None => None
    },
    protectedWithin match {
      case Some(t) => Some(convertTypeToReference(reflect)(t))
      case None => None
    })
  }

  private def extractComments(reflect: Reflection)(comment: Option[reflect.Comment], rep: Representation) : Option[Comment] = {
    import reflect._
    comment match {
      case Some(com) =>
        val parsed = parse(Map.empty, clean(com.raw), com.raw)
        if (true) { //TODO option of tool
          Some(WikiComment(rep, parsed).comment)
        }
        else{
          Some(MarkdownComment(rep, parsed).comment)
        }
      case None => None
    }
  }

  private def extractMembers(reflect: Reflection)(body: List[reflect.Statement], symbol: reflect.ClassDefSymbol) : List[Representation] = {
    import reflect._
    (body.flatMap{
        case IsDefDef(_) => None //No definitions, they are appended with symbol.methods below
        case x => Some(x)
      }.filter{x => //Filter fields which shouldn't be displayed in the doc
        !x.symbol.flags.is(Flags.Local) && //Locally defined
        !x.symbol.flags.is(Flags.Private)
      }
      .map(convertToRepresentation(reflect)) ++
    symbol.methods.map{x => convertToRepresentation(reflect)(x.tree)})
    .sortBy(_.name)
  }

  private def extractParents(reflect: Reflection)(parents: List[reflect.Tree]): List[Reference] = {
    import reflect._

    val parentsReferences = parents.map{
      case reflect.IsTypeTree(c) => convertTypeToReference(reflect)(c.tpe)
      case reflect.IsTerm(c) => convertTypeToReference(reflect)(c.tpe)
      case _ => throw Exception("Unhandeld case in parents. Please open an issue.")
    }
    if(parentsReferences.nonEmpty) parentsReferences.tail else Nil //Object is always at head position and it's pointless to display it
  }

  private def convertTypeToReference(reflect: Reflection)(tp: reflect.Type): Reference = {
    import reflect._

    def typeOrBoundsHandling(typeOrBounds: reflect.TypeOrBounds): Reference = typeOrBounds match {
      case reflect.IsType(tpe) => inner(tpe)
      case reflect.IsTypeBounds(reflect.TypeBounds(low, hi)) => BoundsReference(inner(low), inner(hi))
      case reflect.NoPrefix() => EmptyReference
    }

    //Inner method to avoid passing the reflection each time
    def inner(tp: reflect.Type): Reference = tp match {
      case reflect.Type.IsOrType(reflect.Type.OrType(left, right)) => OrTypeReference(inner(left), inner(right))
      case reflect.Type.IsAndType(reflect.Type.AndType(left, right)) => AndTypeReference(inner(left), inner(right))
      case reflect.Type.IsByNameType(reflect.Type.ByNameType(tpe)) => ByNameReference(inner(tpe))
      case reflect.Type.IsConstantType(reflect.Type.ConstantType(constant)) => ConstantReference(constant.value.toString) //TOASK What is constant
      case reflect.Type.IsThisType(reflect.Type.ThisType(tpe)) => inner(tpe)
      case reflect.Type.IsAnnotatedType(reflect.Type.AnnotatedType(tpe, _)) => inner(tpe)
      case reflect.Type.IsTypeLambda(reflect.Type.TypeLambda(paramNames, paramTypes, resType)) => ConstantReference(removeColorFromType(tp.show)) //TOFIX
      case reflect.Type.IsAppliedType(reflect.Type.AppliedType(tpe, typeOrBoundsList)) => inner(tpe) match {
        case TypeReference(label, link, _, hasOwnFile) =>
          if(link == "./scala"){
            if(label.matches("Function[1-9]") || label.matches("Function[1-9][0-9]")){
              val argsAndReturn = typeOrBoundsList.map(typeOrBoundsHandling)
              FunctionReference(argsAndReturn.take(argsAndReturn.size - 1), argsAndReturn.last, false) //TODO: Implict
            }else if(label.matches("Tuple[1-9]") || label.matches("Tuple[1-9][0-9]")){
              TupleReference(typeOrBoundsList.map(typeOrBoundsHandling))
            }else{
              TypeReference(label, link, typeOrBoundsList.map(typeOrBoundsHandling), hasOwnFile)
            }
          }else{
            TypeReference(label, link, typeOrBoundsList.map(typeOrBoundsHandling), hasOwnFile)
          }
        case _ => throw Exception("Match error in AppliedType. This should not happen, please open an issue. " + tp)
      }

      case reflect.Type.IsTypeRef(reflect.Type.TypeRef(typeName, qual)) =>
        typeOrBoundsHandling(qual) match {
          case TypeReference(label, link, xs, _) => TypeReference(typeName, link + "/" + label, xs, true) //TODO check hasOwnFile
          case EmptyReference => TypeReference(typeName, ".", Nil, true) //TODO check hasOwnFile
          case _ => throw Exception("Match error in TypeRef. This should not happen, please open an issue. " + typeOrBoundsHandling(qual))
        }
      case reflect.Type.IsTermRef(reflect.Type.TermRef(typeName, qual)) =>
        typeOrBoundsHandling(qual) match {
          case TypeReference(label, link, xs, _) => TypeReference(typeName, link + "/" + label, xs)
          case EmptyReference => TypeReference(typeName, ".", Nil)
          case _ => throw Exception("Match error in TermRef. This should not happen, please open an issue. " + typeOrBoundsHandling(qual))
        }
      case reflect.Type.IsSymRef(reflect.Type.SymRef(symbol, typeOrBounds)) => symbol match {
        case reflect.IsClassDefSymbol(_) =>
          typeOrBoundsHandling(typeOrBounds) match {
            case TypeReference(label, link, xs, _) => TypeReference(symbol.name, link + "/" + label, xs, true) //TOASK: Should we include case as own file?
            case EmptyReference if symbol.name == "<root>" | symbol.name == "_root_" => EmptyReference //TOASK: Not consistant root
            case EmptyReference => TypeReference(symbol.name, ".", Nil, true)
            case _ => throw Exception("Match error in SymRef/TypeOrBounds. This should not happen, please open an issue. " + typeOrBoundsHandling(typeOrBounds))
          }
        case reflect.IsPackageDefSymbol(_) | reflect.IsTypeDefSymbol(_) | reflect.IsValDefSymbol(_) =>
          typeOrBoundsHandling(typeOrBounds) match {
            case TypeReference(label, link, xs, _) => TypeReference(symbol.name, link + "/" + label, xs)
            case EmptyReference if symbol.name == "<root>" | symbol.name == "_root_" => EmptyReference
            case EmptyReference => TypeReference(symbol.name, ".", Nil)
            case _ => throw Exception("Match error in SymRef/TypeOrBounds. This should not happen, please open an issue. " + typeOrBoundsHandling(typeOrBounds))
          }
        case _ => throw Exception("Match error in SymRef. This should not happen, please open an issue. " + symbol)
      }
      case _ => throw Exception("No match for type in conversion to Reference. This should not happen, please open an issue. " + tp)
    }

    inner(tp)
  }

  class PackageRepresentation(reflect: Reflection, internal: reflect.PackageClause) extends Representation with Members {
    import reflect._

    override val (name, path) = {
      val pidSplit = internal.pid.symbol.show.split("\\.")
      (pidSplit.last, pidSplit.init.toList)
    }
    override val members = internal.stats.map(convertToRepresentation(reflect)(_))
    override val comments = extractComments(reflect)(internal.symbol.comment, this)
  }

  //TODO: Handle impliedOnly
  class ImportRepresentation(reflect: Reflection, internal: reflect.Import) extends Representation {
    import reflect._

    override val name = if (internal.selectors.size > 1){
        internal.selectors.map(_.toString).mkString("{", ", ", "}")
      } else {
        internal.selectors.head.toString
      }
    override val path = internal.expr.symbol.show.split("\\.").toList
    override val comments = extractComments(reflect)(internal.symbol.comment, this)
  }

  class ClassRepresentation(reflect: Reflection, internal: reflect.ClassDef, val isCase: Boolean = false, val isTrait: Boolean = false) extends Representation with Members with Parents with Modifiers with Companion with Constructors with TypeParams with Annotations {
    import reflect._

    override val name = internal.name
    override val path = extractPath(reflect)(internal.symbol)
    override val members = extractMembers(reflect)(internal.body, internal.symbol)
    override val parent = None
    override val parents = extractParents(reflect)(internal.parents)
    override val (modifiers, privateWithin, protectedWithin) = extractModifiers(reflect)(internal.symbol.flags, internal.symbol.privateWithin, internal.symbol.protectedWithin)
    override val companionPath = internal.symbol.companionClass match { //TOASK: Right way?
      case Some(x) => path.init ++ List(name)
      case None => Nil
    }
    override val constructors = (convertToRepresentation(reflect)(internal.constructor) ::
    (internal.body.filter{x =>
        val noColorshow = removeColorFromType(x.show)
        noColorshow.contains("def this(") || noColorshow.contains("def this[") //For performance use this to filter before mapping instead of using name == "<init>"
      }
      .map(convertToRepresentation(reflect)(_))
    )).flatMap{r => r match {
      case r: DefRepresentation => Some(r)
      case _ => None
      }
    }.map(r => (new MultipleParamList{val paramLists = r.paramLists}, r.comments))
    override val typeParams = internal.constructor.typeParams.map(x => removeColorFromType(x.show).stripPrefix("type "))
    override val annotations = Nil

    override val comments = extractComments(reflect)(internal.symbol.comment, this)
  }

  class ObjectRepresentation(reflect: Reflection, internal: reflect.ClassDef, val isCase: Boolean = false) extends Representation with Modifiers with Members with Parents with Companion with Annotations {
    import reflect._

    override val name = internal.name
    override val path = extractPath(reflect)(internal.symbol)
    override val (modifiers, privateWithin, protectedWithin) = extractModifiers(reflect)(internal.symbol.flags, internal.symbol.privateWithin, internal.symbol.protectedWithin)
    override val members = extractMembers(reflect)(internal.body, internal.symbol)
    override val parent = None
    override val parents = extractParents(reflect)(internal.parents)
    override val companionPath = internal.symbol.companionClass match { //TOASK: Right way?
      case Some(_) => path.init ++ List(name)
      case None => Nil
    }
    override val annotations = Nil
    override val comments = extractComments(reflect)(internal.symbol.comment, this)
  }

  class DefRepresentation(reflect: Reflection, internal: reflect.DefDef) extends Representation with Modifiers with TypeParams with MultipleParamList with ReturnValue with Annotations{
    import reflect._

    override val name = internal.name
    override val path = extractPath(reflect)(internal.symbol)
    override val (modifiers, privateWithin, protectedWithin) = extractModifiers(reflect)(internal.symbol.flags, internal.symbol.privateWithin, internal.symbol.protectedWithin)
    override val typeParams = internal.typeParams.map(x => removeColorFromType(x.show).stripPrefix("type "))

    override val paramLists = internal.paramss.map{p =>
      new ParamList {
        override val list = p.map(x => NamedReference(x.name, convertTypeToReference(reflect)(x.tpt.tpe)))
        override val isImplicit = if(p.size > 1) p.tail.head.symbol.flags.is(Flags.Implicit) else false //TODO: Verfiy this
      }
    }
    override val returnValue = convertTypeToReference(reflect)(internal.returnTpt.tpe)
    override val annotations = Nil
    override val comments = extractComments(reflect)(internal.symbol.comment, this)
  }

  class ValRepresentation(reflect: Reflection, internal: reflect.ValDef) extends Representation with Modifiers with ReturnValue with Annotations {
    import reflect._

    override val name = internal.name
    override val path = extractPath(reflect)(internal.symbol)
    override val (modifiers, privateWithin, protectedWithin) = extractModifiers(reflect)(internal.symbol.flags, internal.symbol.privateWithin, internal.symbol.protectedWithin)
    override val returnValue = convertTypeToReference(reflect)(internal.tpt.tpe)
    override val annotations = Nil
    override val comments = extractComments(reflect)(internal.symbol.comment, this)
  }

  class TypeRepresentation(reflect: Reflection, internal: reflect.TypeDef) extends Representation with Modifiers with TypeParams with Annotations {
    import reflect._

    override val name = internal.name
    override val path = extractPath(reflect)(internal.symbol)
    override val (modifiers, privateWithin, protectedWithin) = extractModifiers(reflect)(internal.symbol.flags, internal.symbol.privateWithin, internal.symbol.protectedWithin)
    override val typeParams = Nil
    override val annotations = Nil
    val alias: Option[Reference] = None //TODO
    def isAbstract: Boolean = !alias.isDefined
    override val comments = extractComments(reflect)(internal.symbol.comment, this)
  }

  def convertToRepresentation(reflect: Reflection)(tree: reflect.Tree) = {
    import reflect._

    tree match {
      case IsPackageClause(t@reflect.PackageClause(_)) => new PackageRepresentation(reflect, t)

      case IsImport(t@reflect.Import(_)) => new ImportRepresentation(reflect, t)

      case IsClassDef(t@reflect.ClassDef(_)) =>
        if(t.symbol.flags.is(Flags.Object)){
          new ObjectRepresentation(reflect, t, t.symbol.flags.is(Flags.Case))
        }else{
          new ClassRepresentation(reflect, t, t.symbol.flags.is(Flags.Case), t.symbol.flags.is(Flags.Trait))
        }
      case IsDefDef(t@reflect.DefDef(_)) => new DefRepresentation(reflect, t)

      case IsValDef(t@reflect.ValDef(_)) => new ValRepresentation(reflect, t) //TODO: contains object too, separate from Val

      case IsTypeDef(t@reflect.TypeDef(_)) => new TypeRepresentation(reflect, t)

      case _ => throw new Exception("Tree match error in conversion to representation. Please open an issue.")
  }}
}