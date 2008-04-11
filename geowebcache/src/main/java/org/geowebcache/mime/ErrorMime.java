/**
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * @author Arne Kepp, The Open Planning Project, Copyright 2008
 *  
 */
package org.geowebcache.mime;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class ErrorMime extends MimeType {
    private static Log log = LogFactory.getLog(org.geowebcache.mime.ErrorMime.class);
    
    public ErrorMime(String mimeType, String extension, String internalName) {
        super(mimeType, extension, internalName, false);
    }
    
    public static ErrorMime createFromMimeType(String mimeType) {
        if (mimeType.equalsIgnoreCase("application/vnd.ogc.se_inimage")) {
            return new ErrorMime("application/vnd.ogc.se_inimage",null,null);
        } else {
            log.error("Unsupported MIME type: " + mimeType + ", falling back to application/vnd.ogc.se_inimage.");
            return new ErrorMime("application/vnd.ogc.se_inimage", null, null);
        }
    }
}
