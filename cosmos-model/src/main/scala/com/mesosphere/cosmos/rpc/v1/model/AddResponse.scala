package com.mesosphere.cosmos.rpc.v1.model

import com.mesosphere.universe

// This class is only a wrapper for the underlying package
// We need a unique type so that the media type encoder can be resolved implicitly
final class AddResponse(val v3Package: universe.v3.model.V3Package) extends AnyVal
