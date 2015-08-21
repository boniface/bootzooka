package com.softwaremill.bootzooka.service.user

import java.util.UUID

import com.softwaremill.bootzooka.common.{Clock, Utils}
import com.softwaremill.bootzooka.dao.UserDao
import com.softwaremill.bootzooka.domain.User
import com.softwaremill.bootzooka.service.data.UserJson
import com.softwaremill.bootzooka.service.email.EmailService
import com.softwaremill.bootzooka.service.templates.EmailTemplatingEngine

import scala.concurrent.{ExecutionContext, Future}

// format: OFF
class UserService(
    userDao: UserDao,
    emailService: EmailService,
    emailTemplatingEngine: EmailTemplatingEngine)
   (implicit ec: ExecutionContext, clock: Clock) {

  // format: ON
  def load(userId: userDao.UserId) = {
    userDao.findById(userId).map(toUserJson)
  }

  def registerNewUser(login: String, email: String, password: String): Future[Unit] = {
    val salt = Utils.randomString(128)
    val now = clock.nowUtc
    val userCreatation: Future[Unit] = userDao.add(User.withRandomUUID(login, email.toLowerCase, password, salt, now))
    userCreatation.onSuccess {
      case _ =>
        val confirmationEmail = emailTemplatingEngine.registrationConfirmation(login)
        emailService.scheduleEmail(email, confirmationEmail)
    }
    userCreatation
  }

  def authenticate(login: String, nonEncryptedPassword: String): Future[Option[UserJson]] = {
    userDao.findByLoginOrEmail(login).map(userOpt =>
      toUserJson(userOpt.filter(u => User.passwordsMatch(nonEncryptedPassword, u))))
  }

  def findByLogin(login: String): Future[Option[UserJson]] = userDao.findByLowerCasedLogin(login).map(toUserJson)

  def findByEmail(email: String): Future[Option[UserJson]] = userDao.findByEmail(email.toLowerCase).map(toUserJson)

  private def toUserJson(userOpt: Option[User]) = userOpt.map(UserJson(_))

  def checkUserExistenceFor(userLogin: String, userEmail: String): Future[Either[String, Unit]] = {
    val existingLoginFuture = findByLogin(userLogin)
    val existingEmailFuture = findByEmail(userEmail)

    for {
      existingLoginOpt <- existingLoginFuture
      existingEmailOpt <- existingEmailFuture
    } yield {
      existingLoginOpt.map(_ => Left("Login already in use!")).orElse(
        existingEmailOpt.map(_ => Left("E-mail already in use!"))
      ).getOrElse(Right((): Unit))
    }
  }

  def changeLogin(userId: UUID, newLogin: String): Future[Either[String, Unit]] = {
    findByLogin(newLogin).flatMap {
      case Some(u) => Future {
        Left("Login is already taken")
      }
      case None => userDao.changeLogin(userId, newLogin).map(Right(_))
    }
  }

  def changeEmail(userId: UUID, newEmail: String): Future[Either[String, Unit]] = {
    findByEmail(newEmail).flatMap {
      case Some(u) => Future {
        Left("E-mail used by another user")
      }
      case None => userDao.changeEmail(userId, newEmail).map(Right(_))
    }
  }

  def changePassword(userId: UUID, currentPassword: String, newPassword: String): Future[Either[String, Unit]] = {
    userDao.findById(userId).flatMap {
      case Some(u) => if (User.passwordsMatch(currentPassword, u)) {
        userDao.changePassword(u.id, User.encryptPassword(newPassword, u.salt)).map(Right(_))
      }
      else {
        Future {
          Left("Current password is invalid")
        }
      }
      case None => Future {
        Left("User not found hence cannot change password")
      }
    }
  }

}
