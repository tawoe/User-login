/**
Open Bank Project - API
Copyright (C) 2011, 2013, TESOBE / Music Pictures Ltd

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

Email: contact@tesobe.com
TESOBE / Music Pictures Ltd
Osloerstrasse 16/17
Berlin 13359, Germany

  This product includes software developed at
  TESOBE (http://www.tesobe.com/)
  by
  Ayoub Benali: ayoub AT tesobe DOT com
  Nina Gänsdorfer: nina AT tesobe DOT com

 */
package code.snippet

import code.model.dataAccess.ResourceUser
import code.model.{CurrentUser, GermanBanks, RequestToken, Token}
import code.pgp.PgpEncryption
import code.util.{Helper, MessageSender}
import com.tesobe.model.AddBankAccountCredentials
import net.liftweb.common._
import net.liftweb.http.js.JsCmds._
import net.liftweb.http.js.{JsCmd, JsCmds}
import net.liftweb.http.{LiftRules, RequestVar, S, SHtml}
import net.liftweb.util.Helpers._
import net.liftweb.util._

import scala.util.{Either, Left, Right}
import scala.xml.{NodeSeq, Unparsed}


class BankingCredentials extends Loggable{
  private lazy val NOOP_SELECTOR = "#i_am_an_id_that_should_never_exist" #> ""

  class FormField[T](
    val defaultValue: T,
    //the message in case the field value is not valid
    val errorMessage: String,
    //the id of the error notice node (span/div) in the template where
    //to show the error message
    val errorNodeId: String
  ) extends RequestVar[T](defaultValue){
    /**
    * Left (the error case) contains the pair (error node Id,  error message)
    * Right Unit
    */
    def validate : Either[(String, String),Unit] =
      if(this.is == defaultValue)
        Left((this.errorNodeId, errorMessage))
      else
        Right()
  }

  private val defaultCountry = S.??("choose_country")
  private val defaultBank = S.??("choose_bank")

  private object country extends FormField[String](
    defaultCountry,
     S.??("no_country_selected"),
    "countryError"
  )
  private object bank extends FormField[String](
    defaultBank,
    S.??("no_bank_selected"),
    "bankError"
  )
  private object accountNumber extends FormField[String](
    "",
    S.??("account_number_empty"),
    "accountNumberError"
  )
  private object userId extends FormField[String](
    "",
    S.??("user_id_empty"),
    "userIdError"
  ){
    override def validate : Either[(String, String),Unit] = {
      val userIdRequired: Boolean =
        GermanBanks
        .getAvaliableBanks()
        .get(bank.get)
        .map{_.userIdRequired}
        .getOrElse(false) // if we don't find a bank
                          // given an id it means we didn't select one
                          // so there is no error message to show yet

      if(userIdRequired && (this.is == this.defaultValue))
        Left((this.errorNodeId, this.errorMessage))
      else
        Right()
    }
  }
  private object accountPin extends FormField[String](
    "",
    S.??("account_pin_empty"),
    "accountPinError"
  )

  //TODO: user Scala macros / reflection to populate this list automatically ?
  private val fields =
    Seq(
    country,
    bank,
    accountNumber,
    accountPin,
    userId
  )

  /**
  * go through the fields and collect the Left cases (error)
  */
  private def validate(fields: Seq[FormField[_]]) : Seq[(String,String)] = {
    fields
      .map{_.validate}
      .collect{
        case l: Left[(String,String), Unit] => l.a
      }
  }

  private def processData(t: Token, u: ResourceUser): String = {
    val id = randomString(8)
    val publicKey = Props.get("publicKeyPath").getOrElse("")
    //TODO: wrap the encryption with a tryo to Handel exception
    val encryptedPin =
      PgpEncryption.encryptToString(accountPin.is, publicKey)
    val accountOwnerId = u.idGivenByProvider
    val accountOwnerProvider = u.provider
    val bankName = GermanBanks
      .getAvaliableBanks()
      .get(bank.get)
      .map{_.name}
      .getOrElse("")

    val optionalUserId = if(userId.get.isEmpty) None else Some(userId.get)
    val message =
      AddBankAccountCredentials(
        id,
        accountNumber,
        optionalUserId,
        bank.get,
        bankName,
        encryptedPin,
        accountOwnerId,
        accountOwnerProvider
      )
    MessageSender.sendBankingCredentials(message)
    id
  }

  private def updatePage(messageId: String): Box[Unit] = {
    import code.util.ResponseAMQPListener
    import com.tesobe.model.{ErrorResponse, Response, SuccessResponse}
    import net.liftweb.actor.{LAFuture, LiftActor}

    val response: LAFuture[Response] = new LAFuture()
    // watingActor waits for acknowledgment if the bank account was added
    object waitingActor extends LiftActor{
      def messageHandler = {
        case r: Response => {
          response.complete(Full(r))
        }
      }
    }
    new ResponseAMQPListener(waitingActor, messageId)

    //TODO: change that to be asynchronous
    // we try to wait for the response for a amount time that is less than
    // lift ajax post timeout
    response.get(LiftRules.ajaxPostTimeout - 2000) match {
      case Full(r) =>
        r match {
          case _: SuccessResponse => {
            logger.info("account saved")
            Full({})
          }
          case _: ErrorResponse =>{
            logger.info(s"could not save account: ${r.message}")
            Failure(r.message)
          }
        }
      case _ => {
        logger.warn("data storage time out.")
        Failure("Failed to save the bank account credentials. Please try later.")
      }
    }

  }

  private def generateVerifier(token: Token, user: ResourceUser) :Box[String] = {
    if (token.verifier.isEmpty) {
      val randomVerifier = token.gernerateVerifier
      token.userForeignKey(user.id)
      if (token.save())
        Full(randomVerifier)
      else{
        logger.warn(s"Could not save token ${token.key}")
        Empty
      }
    }
    else
      Full(token.verifier)
  }

  private def renderForm(token: Token, user: ResourceUser) = {
    lazy val textMessage = <div>Please come back to the application where you come from and enter the following code: </div>
    def verifierMessage(verifier: String) = <div id="verifier">{verifier}</div>

    /**
    *
    */
    def processInputs(): JsCmd = {
      val errors = validate(fields)
      if(errors.isEmpty){
        val messageId = processData(token,user)
        updatePage(messageId) match {
          case Full(_) => {
            //redirect or show the verifier
            generateVerifier(token, user) match {
              case Full(v) => {
                if (token.callbackURL.is == "oob"){
                  JsHideId("error") &
                  SetHtml("verifierBloc",textMessage ++ verifierMessage(v)) &
                  Helper.JsHideByClass("hide-during-ajax")
                }
                else {
                  //redirect the user to the application with the verifier
                  val redirectionUrl =
                    Helpers.appendParams(
                      token.callbackURL,
                      Seq(("oauth_token", token.key),("oauth_verifier", v))
                    )
                  S.redirectTo(redirectionUrl)
                  Noop
                }
              }
              case _ => {
                SetHtml("error", Unparsed(S.??("error_try_later"))) &
                Helper.JsHideByClass("hide-during-ajax")
              }
            }
          }
          case Failure(msg,_, _) => {
            //show an error message other wise
            val error = SHtml.span(Unparsed(msg), Noop, ("class","error"))
            SetHtml("error", error) &
            Helper.JsHideByClass("hide-during-ajax")
          }
          case _ => {
            val error = <span class="error">S.??("error_try_later")</span>
            SetHtml("error",error) &
            Helper.JsHideByClass("hide-during-ajax")
          }
        }
      }
      else{
        errors.foreach{
          e => S.error(e._1, e._2)
        }
        Helper.JsShowByClass("hide-during-ajax")
      }
    }

    val countries = defaultCountry :: S.??("germany") :: Nil
    val availableBanks: Map[String, (String, Boolean)] = GermanBanks.getAvaliableBanks() map {
        case (bankId, bankDetails) => {
          (s"${bankDetails.name} ($bankId)", (bankId,bankDetails.userIdRequired))
        }
      }

    //skip the form if the correspondent query parameter is set
    val skipQuerryParam = "skip-banking-form"
    S.param(skipQuerryParam) match {
      case Full("true") => {
        //generate verifier and redirect/show it
        generateVerifier(token, user) match {
          case Full(v) => {
            if (token.callbackURL.is == "oob")
              "#error" #> "" &
              "#verifierBloc" #> {textMessage ++ verifierMessage(v)} &
              ".hide-during-ajax" #> ""
            else {
              //redirect the user to the application with the verifier
              val redirectionUrl =
                Helpers.appendParams(
                  token.callbackURL,
                  Seq(("oauth_token", token.key),("oauth_verifier", v))
                )
              logger.info(s"redirecting to: $redirectionUrl")
              S.redirectTo(redirectionUrl)
              NOOP_SELECTOR
            }
          }
          case _ => {
            "#error" #> S.??("error_try_later") &
            ".hide-during-ajax" #> ""
          }
        }
      }
      case _ => {

        val banks: Seq[String] =  Seq(defaultBank) ++ availableBanks.keySet.toSeq.sortWith(_.toLowerCase < _.toLowerCase)
        val currentPageWithSkipParam = appendQueryParameters(S.uri, List((skipQuerryParam,"true")))
        def showOrHideUserIdField(bankName: String): JsCmd = {
          val jsCmd = availableBanks.get(bankName) match {
              case Some((bankId, userIdRequired)) =>{
                if(userIdRequired){
                  JsShowId("userIdRow")
                }
                else{
                  JsHideId("userIdRow")
                }
              }
              case _ => Noop
            }
          jsCmd &
          Helper.JsShowByClass("hide-during-ajax")
        }

        "form [action]" #> {S.uri}&
        "#countrySelect"  #>
          SHtml.selectElem(countries,Full(country.is))(
            (v : String) => country.set(v)
          ) &
        "#bankSelect" #> SHtml.selectElem(banks,Full(banks.head))(
            (bankname : String) => availableBanks.get(bankname) map {
                bankIdAndUserIdRequired => bank.set(bankIdAndUserIdRequired._1)
              }
          ) andThen
        "#bankSelect [onchange]" #>{
            import net.liftweb.http.js.JE.JsRaw

            SHtml.ajaxCall(JsRaw("getBankName()"), (bankname) => showOrHideUserIdField(bankname))
        }&
        "#accountNumber" #> SHtml.textElem(accountNumber,("placeholder","123456789")) &
        "#userId" #> SHtml.textElem(userId,("placeholder","user-1234")) &
        "#accountPin" #> SHtml.passwordElem(accountPin,("placeholder","***********")) &
        "#processSubmit" #> SHtml.hidden(processInputs) &
        "#accountInfo [title]" #> S.??("accountInfo") &
        "#saveBtn [value]" #> S.??("save") &
        //this button is redirecting to the same page with the "skip" query parameter.
        //TODO: improve this
        "#skipButton" #> SHtml.ajaxButton(S.??("skip"), ()=> RedirectTo(currentPageWithSkipParam))

      }
    }
  }

  def render = {
    def hideCredentialsForm() = {
      "#credentialsForm" #> NodeSeq.Empty
    }

    RequestToken.is match {
      case Full(token) if(token.isValid) =>
        CurrentUser.is match {
          case Full(user) => {
            renderForm(token, user)
          }
          case _ => {
            S.error("error", S.??("login_required"))
            hideCredentialsForm()
          }
        }
      case Full(token) => {
        S.error("error", "Token expired")
        hideCredentialsForm()
      }
      case _ =>{
        S.error("error", "Token not found")
        hideCredentialsForm()
      }
    }
  }
}