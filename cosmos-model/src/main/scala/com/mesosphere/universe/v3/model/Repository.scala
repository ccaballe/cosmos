package com.mesosphere.universe.v3.model

/**
  * Conforms to: https://universe.mesosphere.com/v3/schema/repo
  */
case class Repository(packages: List[PackageDefinition])
