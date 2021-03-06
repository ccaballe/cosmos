package com.mesosphere.cosmos.storage

import com.mesosphere.cosmos.http.MediaType
import com.twitter.util.Future
import java.io.InputStream
import java.util.UUID

final class StagedPackageStorage private(objectStorage: ObjectStorage) {

  def get(packageId: UUID): Future[(MediaType, InputStream)] = {
    // TODO package-add: Test for failure cases
    objectStorage.read(packageId.toString).map(_.get)
  }

  def put(packageData: InputStream, packageSize: Long, mediaType: MediaType): Future[UUID] = {
    val id = UUID.randomUUID()
    objectStorage.write(id.toString, packageData, packageSize, mediaType).before(Future.value(id))
  }

}

object StagedPackageStorage {

  def apply(objectStorage: ObjectStorage): StagedPackageStorage = {
    new StagedPackageStorage(objectStorage)
  }

}
