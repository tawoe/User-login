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

import net.liftweb.common.{Full, Box, Empty, Failure, Loggable}
import net.liftweb.http.S
import net.liftweb.util.{Helpers, CssSel}
import scala.xml.NodeSeq
import net.liftweb.util.Helpers._
import net.liftweb.mapper.By
import java.net.URL
import net.liftweb.http.js.JsCmds.RedirectTo
import Helpers.tryo

import code.model.dataAccess.ResourceUser
import code.model.Token
import code.model.{RequestToken, CurrentUser}

class OAuthCallback extends Loggable{
  private val NOOP_SELECTOR = "#i_am_an_id_that_should_never_exist" #> ""

  def redirectToBankingCredentials:CssSel = {
    if(S.post_?){
      val result =
        for{
          tokenParam <- checkParameter("token")
          userIdGiverByProvider <- checkParameter("user_id")
          token <- getToken(tokenParam)
          url <- getAuthenticationURL(token)
          providerId <- getProviderId(url)
        } yield{
          val user = getOrCreateAPIUser(userIdGiverByProvider, providerId)
          setSessionVars(user, token)
        }

      result match {
        case Full(a) => {
          val queryString = S.queryString.getOrElse("")
          S.redirectTo(s"../banking-credentials?$queryString")
        }
        case Failure(msg, _, _) => S.error("error", msg)
        case _ => S.error("error", "could not register user.")
      }
      NOOP_SELECTOR
    }
    else{
      S.error("error", "no POST request found")
      NOOP_SELECTOR
    }
  }

  private def checkParameter(param: String): Box[String] = {
    S.param(param) match {
      case Full(value) if(value.nonEmpty) => {
        logger.info(s"$param parameter is set")
        Full(value)
      }
      case Full(value) => {
        val error = s"$param value is empty"
        logger.error(error)
        Failure(error)
      }
      case _ => {
        val error = s"$param parameter is missing"
        logger.error(error)
        Failure(error)
      }
    }

  }
  private def getToken(token: String): Box[Token] = {
    Token.find(By(Token.thirdPartyApplicationSecret, token)) match {
      case Full(token) => {
        if(token.isValid)
          Full(token)
        else
          Failure("token expired")
      }
      case _ =>
        Failure("token not found")
    }
  }

  private def getAuthenticationURL(token: Token): Box[String] = {
    token.consumerId.obj match {
      case Full(consumer) =>
        Full(consumer.userAuthenticationURL.get)
      case _ =>
        Failure("consumer not found.")
    }
  }


  private def getProviderId(authenticationURL: String) : Box[String] ={
    if(authenticationURL.nonEmpty){
      tryo{
        new URL(authenticationURL)
      } match {
        case Full(url) =>{
          val newURL = url.getProtocol() + "://" + url.getHost()
          Full(newURL)
        }
        case _ =>
          Failure("non valid authentication URL. Could not create the User.")
      }
    }
    else
      Failure("authentication URL is empty. Could not create the User.")
  }

  private def setSessionVars(user: ResourceUser, token: Token): Unit = {
    CurrentUser.set(Full(user))
    RequestToken.set(Full(token))
  }

  private def getOrCreateAPIUser(userId: String, host: String): ResourceUser = {
    ResourceUser.find(By(ResourceUser.providerId, userId), By(ResourceUser.provider_, host)) match {
      case Full(u) => {
        logger.info("user exist already")
        u
      }
      case _ => {
        logger.info("creating user")
        ResourceUser.create
        .provider_(host)
        .providerId(userId)
        .saveMe
      }
    }
  }
}